package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.repository.DictationRepository
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.summarizer.Summarizer
import com.nabla.voice.DictationMode
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.TranscriptionService
import com.nabla.voice.TranscriptionServiceConnection
import com.nabla.voice.formatTranscript
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * "service owns the session" design and commit history for the reattach logic, both carried
 * over unchanged here).
 *
 * Real summarization (P5): summarize() calls the injected [Summarizer] with transcriptEntries
 * and typedNotes kept as two separate arguments, not pre-merged — matches 2026-09-16
 * device-testing feedback (an earlier round tried unifying them into one NoteLine list;
 * reverted — the Notes tab is "just a box", independent of the transcript, not interleaved
 * with it). Not ported from chato: its organizeNotes()/summarize() called the OpenClaw gateway
 * directly with a hardcoded prompt in the ViewModel — that provider coupling is exactly what
 * the Summarizer interface exists to avoid. See Summarizer's class doc.
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
    val gatewayUrl: String = "",
    val gatewayToken: String = "",
)

@HiltViewModel
class DictationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictationRepository: DictationRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val summarizer: Summarizer,
) : ViewModel() {

    private val _mode = MutableStateFlow(DictationMode.NOTES)
    val mode: StateFlow<DictationMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<DictationSessionState>(DictationSessionState.Idle)
    val state: StateFlow<DictationSessionState> = _state.asStateFlow()

    private val _transcriptEntries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcriptEntries: StateFlow<List<TranscriptEntry>> = _transcriptEntries.asStateFlow()

    private val _typedNotes = MutableStateFlow("")
    val typedNotes: StateFlow<String> = _typedNotes.asStateFlow()

    // Utterances from inline (dictate-at-cursor) sessions — kept out of transcriptEntries so the
    // Dictation screen's buffer only holds what was dictated there.
    private val _inlineUtterances = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val inlineUtterances: SharedFlow<String> = _inlineUtterances.asSharedFlow()

    private val _summaryText = MutableStateFlow<String?>(null)
    val summaryText: StateFlow<String?> = _summaryText.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _settings = MutableStateFlow(DictationSettings())
    val settings: StateFlow<DictationSettings> = _settings.asStateFlow()

    private var activityRef: WeakReference<Activity>? = null
    // Set by the screen right after navigation, from the folder BrowserViewModel was showing
    // when Dictate was tapped (2026-09-16 device-testing feedback: saves were landing in the
    // app's separately-configured default folder — a different, fixed setting — instead of
    // wherever the user was actually browsing). No fallback to that default on purpose: falling
    // back to it silently would just reintroduce the same bug in a quieter form.
    private var saveFolderPath: String? = null
    private var utteranceCollectorJob: Job? = null
    private val serviceConnection = TranscriptionServiceConnection()
    private var serviceIntent: Intent? = null
    private var typedNotesSaveJob: Job? = null

    init {
        viewModelScope.launch {
            _transcriptEntries.update { dictationRepository.load() }
            _typedNotes.update { dictationRepository.typedNotes() }
            loadSettings()
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

    /** Call once from the screen with the folder the user was browsing when Dictate was tapped. */
    fun setSaveFolder(folderPath: String) {
        saveFolderPath = folderPath
    }

    /** Mode can't change mid-session — the active recognizer engine is fixed for the session. */
    fun setMode(newMode: DictationMode) {
        if (_state.value is DictationSessionState.Recording || _state.value is DictationSessionState.Stopping) return
        _mode.update { newMode }
    }

    // --- Session (Start/Stop dictation) ---

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
            svc.transcriptEntries.collect { allEntries -> _transcriptEntries.update { allEntries } }
        }

        launch {
            svc.inlineUtterances.collect { _inlineUtterances.emit(it) }
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

    /** [inline] = dictate-at-cursor: utterances go to [inlineUtterances], not the transcript. */
    fun startSession(contextNotes: String = "", inline: Boolean = false) {
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
            putExtra(TranscriptionService.EXTRA_INLINE, inline)
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

    // --- Notes tab: just a box (2026-09-16 feedback) ---

    /**
     * Updates immediately for a responsive UI, persists on a debounce — same shape as
     * EditorViewModel's autosave, for the same reason: this can fire on every keystroke, and a
     * DataStore write per keystroke is exactly the hot-path-persistence mistake just fixed in
     * TranscriptionService (see that file's onUtteranceRecognized comment).
     */
    fun updateTypedNotes(text: String) {
        _typedNotes.value = text
        typedNotesSaveJob?.cancel()
        typedNotesSaveJob = viewModelScope.launch {
            delay(1000L)
            dictationRepository.saveTypedNotes(text)
        }
    }

    /** Clears the transcript and the typed notes — a full reset. */
    fun clearTranscript() {
        _transcriptEntries.update { emptyList() }
        _typedNotes.value = ""
        _summaryText.update { null }
        typedNotesSaveJob?.cancel()
        viewModelScope.launch { dictationRepository.clearSession() }
        // If a session is currently active, the service's own cumulative list must be wiped
        // too — otherwise its next StateFlow emission would resurrect the pre-clear entries
        // into the mirror above. No-op if nothing is bound right now.
        serviceConnection.service.value?.clearTranscript()
    }

    fun clearSaveStatus() {
        _saveStatus.update { null }
    }

    // --- Summarize (Summary tab) ---

    /** No-op while already summarizing, or with nothing to summarize. */
    fun summarize() {
        if (_isSummarizing.value) return
        val transcript = _transcriptEntries.value
        val notes = _typedNotes.value
        if (transcript.isEmpty() && notes.isBlank()) return

        viewModelScope.launch {
            _isSummarizing.update { true }
            summarizer.summarize(transcript, notes).fold(
                onSuccess = { summary -> _summaryText.update { summary } },
                onFailure = { e -> _error.update { "Summarize failed: ${e.message}" } },
            )
            _isSummarizing.update { false }
        }
    }

    // --- Save to OneDrive (Summary tab) ---
    // Saves into saveFolderPath — see its doc comment. Three targets, matching chato's
    // original transcript/summary/both choice.

    fun saveTranscriptToOneDrive(title: String) {
        val entries = _transcriptEntries.value
        if (entries.isEmpty()) return
        saveToOneDrive(title, formatTranscript(entries))
    }

    fun saveSummaryToOneDrive(title: String) {
        val summary = _summaryText.value
        if (summary.isNullOrBlank()) return
        saveToOneDrive(title, summary)
    }

    fun saveBothToOneDrive(title: String) {
        val entries = _transcriptEntries.value
        val summary = _summaryText.value
        if (summary.isNullOrBlank() || entries.isEmpty()) return
        val combined = buildString {
            appendLine("## Summary")
            appendLine(summary)
            appendLine()
            appendLine("## Transcript")
            append(formatTranscript(entries))
        }
        saveToOneDrive(title, combined)
    }

    private fun saveToOneDrive(title: String, content: String) {
        val activity = activityRef?.get() ?: run {
            _saveStatus.update { "Save failed: no Activity to authenticate with" }
            return
        }
        val folderPath = saveFolderPath ?: run {
            _saveStatus.update { "Save failed: no folder set (open Dictation from the file browser)" }
            return
        }
        viewModelScope.launch {
            try {
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
                gatewayUrl = dictationRepository.gatewayUrl(),
                gatewayToken = dictationRepository.gatewayToken(),
            )
        }
    }

    fun saveAzureSettings(key: String, region: String) {
        viewModelScope.launch {
            dictationRepository.saveAzureSettings(key, region)
            loadSettings()
        }
    }

    fun saveGatewaySettings(url: String, token: String) {
        viewModelScope.launch {
            dictationRepository.saveGatewaySettings(url, token)
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
