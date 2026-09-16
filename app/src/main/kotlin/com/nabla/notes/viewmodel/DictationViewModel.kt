package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.model.NoteLine
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Ported from nabla-chato-voice's DictationViewModel (see that repo's commit d8e8dc9 for the
 * "service owns the session" design and commit history for the reattach/fold-reconciliation
 * logic, both carried over unchanged here).
 *
 * Deliberately NOT ported: organizeNotes()/summarize(), which called chato's OpenClaw gateway
 * to LLM-process the pending text into a structured note. That's the provider-agnostic
 * summarizer seam flagged for a later phase — porting chato's hardcoded-to-one-provider
 * version now just to redesign it later would be wasted work. For now, noteLines (see below,
 * plain text joined) is what gets saved directly — a plainer artifact than chato's
 * LLM-organized note, but a real one, not a stub.
 *
 * noteLines replaced a flat pendingText string 2026-09-15 (device-testing feedback: the
 * separate live transcript view and the pending-notes preview showed the same content twice
 * while purely dictating — pendingText was literally derived from transcriptEntries, see
 * foldNewEntriesIntoNoteLines below — and unifying them into one view meant giving typed
 * entries the timestamp/speaker structure spoken entries already had, which a plain string
 * with embedded 🎙/📝 markers couldn't carry).
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

    private val _noteLines = MutableStateFlow<List<NoteLine>>(emptyList())
    val noteLines: StateFlow<List<NoteLine>> = _noteLines.asStateFlow()

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
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // How many of the service's transcriptEntries this ViewModel has already folded into
    // noteLines. See DictationRepository's KEY_PENDING_FOLDED_COUNT and
    // foldNewEntriesIntoNoteLines() below.
    private var foldedCount: Int = 0

    companion object {
        internal const val PENDING_LINE_BREAK_GAP_MS = 60_000L
    }

    init {
        viewModelScope.launch {
            _transcriptEntries.update { dictationRepository.load() }
            foldedCount = dictationRepository.noteLinesFoldedCount()
            _noteLines.update { dictationRepository.noteLines() }
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
     * ViewModel instance hasn't folded into noteLines yet. Driven off [foldedCount] — a
     * persisted count — rather than reacting to each utterance live, so a gap where nothing
     * was attached still gets reconciled correctly on reattach instead of silently missing
     * content the eventual save/organize step would otherwise never see.
     */
    private fun foldNewEntriesIntoNoteLines(allEntries: List<TranscriptEntry>) {
        if (allEntries.size <= foldedCount) return
        allEntries.drop(foldedCount).forEach { entry ->
            appendNoteLine(timestamp = entry.timestamp, speakerId = entry.speakerId, text = entry.text, typed = false)
        }
        foldedCount = allEntries.size
        viewModelScope.launch { dictationRepository.saveNoteLinesFoldedCount(foldedCount) }
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
                foldNewEntriesIntoNoteLines(allEntries)
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

    // --- Notes: typed input + unified note-lines stream ---

    /** Typed notes join the same stream as dictation, just with speakerId = null. */
    fun addTypedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        appendNoteLine(timestamp = timeFormat.format(Date(clockMs())), speakerId = null, text = trimmed, typed = true)
    }

    /**
     * A pause of [PENDING_LINE_BREAK_GAP_MS] or more before this entry marks it
     * [NoteLine.precededByGap], so the UI can render a visual break — flags a bigger gap than
     * routine pauses between utterances.
     */
    internal fun appendNoteLine(timestamp: String, speakerId: String?, text: String, typed: Boolean) {
        val now = clockMs()
        val gap = lastAppendAtMs != 0L && (now - lastAppendAtMs) >= PENDING_LINE_BREAK_GAP_MS
        _noteLines.update { current ->
            val updated = current + NoteLine(timestamp, speakerId, text, typed, precededByGap = gap)
            viewModelScope.launch { dictationRepository.saveNoteLines(updated) }
            updated
        }
        lastAppendAtMs = now
    }

    /** Clears both the transcript and the note-lines stream derived from it — a full reset. */
    fun clearTranscript() {
        _transcriptEntries.update { emptyList() }
        // dictationRepository.clearSession() below wipes the persisted note lines too, so the
        // in-memory flow has to be reset here in lockstep — previously wasn't, leaving stale
        // text on screen after a clear (2026-09-15 device-testing feedback).
        _noteLines.update { emptyList() }
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
    // Saves into the app's already-configured note folder (SettingsRepository) — dictation
    // doesn't get its own separate folder setting, it shares the one the file browser uses.
    //
    // Only one save action, by design (2026-09-15 device-testing feedback): an earlier version
    // had a second "Save transcript" (timestamp + speaker per line) alongside this one, saving
    // pendingText's plain-prose equivalent. That read as two buttons doing almost the same
    // thing. Saved content stays plain text (no timestamp/speaker) — that decision didn't
    // change when noteLines unified the live view; only the live view did.

    /** Saves the note-lines stream (spoken + typed, plain text) as a new note. */
    fun saveNotesToOneDrive(title: String) {
        val text = _noteLines.value.joinToString("\n") { it.text }
        if (text.isBlank()) return
        saveToOneDrive(title, text)
    }

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
