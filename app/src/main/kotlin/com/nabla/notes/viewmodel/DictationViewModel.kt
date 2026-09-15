package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.repository.DictationRepository
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.repository.SettingsRepository
import com.nabla.voice.DictationMode
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.TranscriptionService
import com.nabla.voice.TranscriptionServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Ported from nabla-chato-voice's DictationViewModel (see that repo's commit d8e8dc9 for the
 * "service owns the session" design and commit history for the reattach/fold-reconciliation
 * logic, both carried over unchanged here).
 *
 * Deliberately NOT ported: organizeNotes()/summarize(), which called chato's OpenClaw gateway
 * to LLM-process the pending text into a structured note. That's the provider-agnostic
 * summarizer seam flagged for a later phase — porting chato's hardcoded-to-one-provider
 * version now just to redesign it later would be wasted work. For now, pendingText (with its
 * 🎙/📝 source markers stripped) is what gets saved directly — a plainer artifact than chato's
 * LLM-organized note, but a real one, not a stub.
 */
sealed class DictationSessionState {
    object Idle : DictationSessionState()
    object Recording : DictationSessionState()
    object Stopping : DictationSessionState()
    data class Error(val message: String) : DictationSessionState()
}

data class DictationSettings(
    val azureSpeechKey: String = "",
    val azureSpeechRegion: String = "eastus",
)

@HiltViewModel
class DictationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictationRepository: DictationRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _mode = MutableStateFlow(DictationMode.NOTES)
    val mode: StateFlow<DictationMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<DictationSessionState>(DictationSessionState.Idle)
    val state: StateFlow<DictationSessionState> = _state.asStateFlow()

    private val _transcriptEntries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcriptEntries: StateFlow<List<TranscriptEntry>> = _transcriptEntries.asStateFlow()

    private val _pendingText = MutableStateFlow("")
    val pendingText: StateFlow<String> = _pendingText.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _settings = MutableStateFlow(DictationSettings())
    val settings: StateFlow<DictationSettings> = _settings.asStateFlow()

    private var activityRef: WeakReference<Activity>? = null
    private var utteranceCollectorJob: Job? = null
    private val serviceConnection = TranscriptionServiceConnection()
    private var serviceIntent: Intent? = null

    /** Overridable in tests to control elapsed-time behavior deterministically. */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }
    private var lastAppendAtMs: Long = 0L

    // How many of the service's transcriptEntries this ViewModel has already folded into
    // pendingText. See DictationRepository's KEY_PENDING_FOLDED_COUNT and
    // foldNewEntriesIntoPending() below.
    private var foldedCount: Int = 0

    companion object {
        private const val SPOKEN_PREFIX = "🎙 "
        private const val TYPED_PREFIX = "📝 "
        internal const val PENDING_LINE_BREAK_GAP_MS = 60_000L
    }

    init {
        viewModelScope.launch {
            _transcriptEntries.update { dictationRepository.load() }
            foldedCount = dictationRepository.pendingTextFoldedCount()
            _pendingText.update { dictationRepository.pendingText() }
            loadSettings()
            // foldedCount must be set before this — see its assignment above. A nested launch
            // inside tryReattachToRunningService doesn't reorder that; it only schedules work
            // that runs later, once a service is actually found and connected.
            tryReattachToRunningService()
        }
    }

    /** Persisted context notes — read by the UI on first composition. */
    suspend fun savedContextNotes(): String = dictationRepository.contextNotes()

    /** Persist context notes immediately on every change. */
    fun saveContextNotes(notes: String) {
        viewModelScope.launch { dictationRepository.saveContextNotes(notes) }
    }

    /** Call from the hosting Activity so MSAL and OneDrive calls have an Activity reference. */
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Mode can't change mid-session — the active recognizer engine is fixed for the session. */
    fun setMode(newMode: DictationMode) {
        if (_state.value is DictationSessionState.Recording || _state.value is DictationSessionState.Stopping) return
        _mode.update { newMode }
    }

    // --- Session (Start/Stop dictation) ---

    /**
     * Fold transcript entries the service accepted that this (or any prior, now-dead)
     * ViewModel instance hasn't folded into pendingText yet. Driven off [foldedCount] — a
     * persisted count — rather than reacting to each utterance live, so a gap where nothing
     * was attached still gets reconciled correctly on reattach instead of silently missing
     * content the eventual save/organize step would otherwise never see.
     */
    private fun foldNewEntriesIntoPending(allEntries: List<TranscriptEntry>) {
        if (allEntries.size <= foldedCount) return
        allEntries.drop(foldedCount).forEach { entry -> appendPendingText(entry.text, typed = false) }
        foldedCount = allEntries.size
        viewModelScope.launch { dictationRepository.savePendingTextFoldedCount(foldedCount) }
    }

    /**
     * Collect [svc]'s flows. Launched as children of the caller's coroutine (an extension on
     * CoroutineScope, not its own viewModelScope.launch) so [startSession] and
     * [tryReattachToRunningService] can each track the whole "wait for svc, then attach" unit
     * as one cancellable [utteranceCollectorJob].
     */
    private fun CoroutineScope.attachToService(svc: TranscriptionService, reattaching: Boolean) {
        // On a fresh startSession() the service isn't recording yet, so the seenTrue-guarded
        // collector below (which ignores isRecording's stale initial value) is exactly right.
        // On reattach the service may already be mid-session — read the real current value
        // directly rather than relying on that guard, whose first-emission handling doesn't
        // apply here.
        if (reattaching) {
            _state.update {
                if (svc.isRecording.value) DictationSessionState.Recording else DictationSessionState.Idle
            }
        }

        launch {
            svc.transcriptEntries.collect { allEntries ->
                _transcriptEntries.update { allEntries }
                foldNewEntriesIntoPending(allEntries)
            }
        }

        launch {
            svc.error.collect { msg ->
                _state.update { DictationSessionState.Error(msg) }
            }
        }

        // Sync recording state from service (e.g., service killed externally).
        // IMPORTANT: skip the initial value (false) — only react to true→false transitions.
        launch {
            var seenTrue = false
            svc.isRecording.collect { recording ->
                if (recording) {
                    seenTrue = true
                } else if (seenTrue && _state.value is DictationSessionState.Recording) {
                    _state.update { DictationSessionState.Idle }
                }
            }
        }
    }

    /**
     * Called once from init. Checks — without creating one — whether TranscriptionService is
     * already running, which happens whenever this ViewModel instance is not the one that
     * started the current session (Activity/task torn down and relaunched mid-dictation, etc.).
     * Passing flags=0 instead of BIND_AUTO_CREATE is what makes this a passive check: Android
     * only binds if the service already exists, and returns false immediately otherwise.
     */
    private fun tryReattachToRunningService() {
        serviceConnection.reset()
        val intent = Intent(context, TranscriptionService::class.java)
        val bound = context.bindService(intent, serviceConnection, /* flags = */ 0)
        if (!bound) return

        serviceIntent = intent
        utteranceCollectorJob?.cancel()
        utteranceCollectorJob = viewModelScope.launch {
            val svc = serviceConnection.service.filterNotNull().first()
            attachToService(svc, reattaching = true)
        }
    }

    fun startSession(contextNotes: String = "") {
        val azureKey = _settings.value.azureSpeechKey
        val azureRegion = _settings.value.azureSpeechRegion

        if (azureKey.isBlank()) {
            _state.update { DictationSessionState.Error("Azure Speech Key not configured. Open Settings.") }
            return
        }

        // Step 1: Immediate UI feedback
        _state.update { DictationSessionState.Recording }

        // Step 2: Reset connection to clear any stale service reference
        // onServiceDisconnected is NOT called on clean unbind, so we must reset manually.
        serviceConnection.reset()

        // Step 3: Cancel any stale collector before starting a new one
        utteranceCollectorJob?.cancel()
        utteranceCollectorJob = null

        val intent = Intent(context, TranscriptionService::class.java).apply {
            putExtra(TranscriptionService.EXTRA_AZURE_KEY, azureKey)
            putExtra(TranscriptionService.EXTRA_AZURE_REGION, azureRegion)
            putExtra(TranscriptionService.EXTRA_CONTEXT_NOTES, contextNotes)
            putExtra(TranscriptionService.EXTRA_MODE, _mode.value.name)
        }
        serviceIntent = intent

        // Step 4: Start collector BEFORE binding so it is already waiting when
        // onServiceConnected fires and emits the fresh service reference
        utteranceCollectorJob = viewModelScope.launch {
            val svc = serviceConnection.service.filterNotNull().first()
            attachToService(svc, reattaching = false)
        }

        // Step 5: Bind service — triggers onServiceConnected → sets serviceConnection.service
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Step 6: Start the foreground service
        context.startForegroundService(intent)
    }

    fun stopSession() {
        utteranceCollectorJob?.cancel()
        utteranceCollectorJob = null

        _state.update { DictationSessionState.Stopping }

        serviceConnection.service.value?.stopTranscription()

        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Not bound -- ignore
        }

        // Do NOT call stopService() here — the service stops itself via stopSelf() inside
        // cleanup() after the async stopTranscribingAsync()/stopContinuousRecognitionAsync()
        // completes. Calling stopService() here races with the async coroutine.
        serviceIntent = null
        serviceConnection.reset()

        _state.update { DictationSessionState.Idle }
    }

    fun dismissError() {
        _error.update { null }
        if (_state.value is DictationSessionState.Error) _state.update { DictationSessionState.Idle }
    }

    // --- Notes: typed input + pending buffer ---

    /** Typed notes join the same pending buffer as dictation. */
    fun addTypedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        appendPendingText(trimmed, typed = true)
    }

    /**
     * Every entry is always its own line, prefixed by source. A pause of
     * [PENDING_LINE_BREAK_GAP_MS] or more before the next entry adds an extra blank line, to
     * flag a bigger gap than routine pauses between utterances.
     */
    internal fun appendPendingText(text: String, typed: Boolean = false) {
        val now = clockMs()
        val entry = "${if (typed) TYPED_PREFIX else SPOKEN_PREFIX}$text"
        _pendingText.update { current ->
            val updated = when {
                current.isBlank() -> entry
                (now - lastAppendAtMs) >= PENDING_LINE_BREAK_GAP_MS -> "$current\n\n$entry"
                else -> "$current\n$entry"
            }
            viewModelScope.launch { dictationRepository.savePendingText(updated) }
            updated
        }
        lastAppendAtMs = now
    }

    /** Strips the 🎙/📝 source markers used only for the on-screen pending preview. */
    private fun stripPendingMarkers(text: String): String =
        text.lines().joinToString("\n") { it.removePrefix(SPOKEN_PREFIX).removePrefix(TYPED_PREFIX) }

    fun clearPendingText() {
        _pendingText.update { "" }
        viewModelScope.launch { dictationRepository.savePendingText("") }
    }

    fun clearTranscript() {
        _transcriptEntries.update { emptyList() }
        foldedCount = 0
        viewModelScope.launch { dictationRepository.clearSession() }
        // If a session is currently active, the service's own cumulative list must be wiped
        // too — otherwise its next StateFlow emission would resurrect the pre-clear entries
        // into the mirror above. No-op if nothing is bound right now.
        serviceConnection.service.value?.clearTranscript()
    }

    fun clearSaveStatus() {
        _saveStatus.update { null }
    }

    // --- Save to OneDrive ---
    // Both save into the app's already-configured note folder (SettingsRepository) — dictation
    // doesn't get its own separate folder setting, it shares the one the file browser uses.

    /** Saves the pending notes buffer (spoken + typed, markers stripped) as a new note. */
    fun saveNotesToOneDrive(title: String) {
        val text = stripPendingMarkers(_pendingText.value)
        if (text.isBlank()) return
        saveToOneDrive(title, text)
    }

    /** Saves the full timestamped transcript as a new note. */
    fun saveTranscriptToOneDrive(title: String) {
        val entries = _transcriptEntries.value
        if (entries.isEmpty()) return
        saveToOneDrive(title, formatTranscript(entries))
    }

    private fun formatTranscript(entries: List<TranscriptEntry>): String =
        entries.joinToString("\n") { "[${it.timestamp}] ${it.speakerId}: ${it.text}" }

    private fun saveToOneDrive(title: String, content: String) {
        val activity = activityRef?.get() ?: run {
            _saveStatus.update { "Save failed: no Activity to authenticate with" }
            return
        }
        viewModelScope.launch {
            try {
                val folderPath = settingsRepository.settings.first().folderPath
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
                oneDriveRepository.createFile(folderPath, "$safeTitle.md", activity).fold(
                    onSuccess = { newFile ->
                        oneDriveRepository.saveFileContent(newFile.id, content, activity).fold(
                            onSuccess = { _saveStatus.update { "Saved to $folderPath/${newFile.name}" } },
                            onFailure = { e -> _saveStatus.update { "Save failed: ${e.message}" } },
                        )
                    },
                    onFailure = { e -> _saveStatus.update { "Save failed: ${e.message}" } },
                )
            } catch (e: Throwable) {
                _saveStatus.update { "Error: ${e::class.simpleName}: ${e.message?.take(100)}" }
            }
        }
    }

    // --- Settings ---

    private suspend fun loadSettings() {
        _settings.update {
            DictationSettings(
                azureSpeechKey = dictationRepository.azureSpeechKey(),
                azureSpeechRegion = dictationRepository.azureSpeechRegion(),
            )
        }
    }

    fun saveAzureSettings(key: String, region: String) {
        viewModelScope.launch {
            dictationRepository.saveAzureSettings(key, region)
            loadSettings()
        }
    }

    override fun onCleared() {
        super.onCleared()
        utteranceCollectorJob?.cancel()
        utteranceCollectorJob = null
        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Not bound -- ignore
        }
    }
}
