package com.nabla.voice

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.microsoft.cognitiveservices.speech.*
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Owns the dictation session end to end, including the accumulated transcript.
 *
 * Ported from nabla-chato-voice as part of merging that app's dictation feature into this
 * one (see that repo's commit d8e8dc9 for the original "why the service owns the session"
 * writeup — unchanged here). Two things were decoupled from the original so this module
 * doesn't carry a dependency on chato's app code:
 *
 *  - Persistence goes through [TranscriptStore], injected via Hilt, instead of a concrete
 *    chato-specific repository. The host app provides the binding.
 *  - The notification's tap target is an optional [PendingIntent] passed via
 *    [EXTRA_CONTENT_INTENT] instead of a hardcoded MainActivity the module doesn't know
 *    about. No intent extra means no tap action, not a crash.
 *
 * transcriptEntries is the session's source of truth — appended and persisted on every
 * accepted utterance regardless of whether anything is currently attached, so a ViewModel can
 * come and go (config changes, task teardown, process trim) without losing anything or leaving
 * the service stuck with no way to reattach.
 */
@AndroidEntryPoint
class TranscriptionService : Service() {

    @Inject
    lateinit var transcriptStore: TranscriptStore

    inner class TranscriptionBinder : Binder() {
        fun getService(): TranscriptionService = this@TranscriptionService
    }

    private val binder = TranscriptionBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Source of truth for the session's transcript — see class doc. Seeded from persisted
    // storage in onCreate so a service that outlives its first ViewModel still has history.
    private val _transcriptEntries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcriptEntries: StateFlow<List<TranscriptEntry>> = _transcriptEntries.asStateFlow()

    // Debounce per speaker: Azure emits individual speaker utterances then a consolidated
    // final within a burst. We debounce PER SPEAKER so that:
    // - Multiple utterances from the same speaker within 300ms → only the last emits
    // - Utterances from different speakers are independent and both emit
    // Notes mode has a single constant "speaker" (NOTES_SPEAKER_ID) and reuses the same logic.
    private val pendingBySpeaker = mutableMapOf<String, String>()   // speakerId → latest text
    private val debounceJobBySpeaker = mutableMapOf<String, Job>()
    private val debounceLock = Any()
    val isRecording = MutableStateFlow(false)
    val error = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Exactly one of these is non-null at a time, depending on the active DictationMode.
    // The SDK has no shared start/stop interface across ConversationTranscriber (diarized,
    // Conversation mode) and SpeechRecognizer (single-speaker, Notes mode).
    private var conversationTranscriber: ConversationTranscriber? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopRequested = false

    companion object {
        const val CHANNEL_ID = "nabla_voice_transcription_channel"
        const val NOTIF_ID = 4001
        const val EXTRA_AZURE_KEY = "azure_key"
        const val EXTRA_AZURE_REGION = "azure_region"
        const val EXTRA_CONTEXT_NOTES = "context_notes"
        const val EXTRA_MODE = "mode"
        /** Optional PendingIntent for the notification's tap action — see class doc. */
        const val EXTRA_CONTENT_INTENT = "content_intent"
        private const val NOTES_SPEAKER_ID = "You"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _transcriptEntries.value = transcriptStore.load()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(extractContentIntent(intent)))

        val azureKey = intent?.getStringExtra(EXTRA_AZURE_KEY) ?: ""
        val azureRegion = intent?.getStringExtra(EXTRA_AZURE_REGION) ?: ""
        val contextNotes = intent?.getStringExtra(EXTRA_CONTEXT_NOTES) ?: ""
        val mode = DictationMode.fromExtra(intent?.getStringExtra(EXTRA_MODE))

        serviceScope.launch {
            startTranscribing(azureKey, azureRegion, contextNotes, mode)
        }
        return START_NOT_STICKY
    }

    /** Shared Azure config for both modes — same locale autodetect, segmentation and profanity settings. */
    private fun buildSpeechConfig(azureKey: String, azureRegion: String): SpeechConfig =
        SpeechConfig.fromSubscription(azureKey, azureRegion).apply {
            // Semantic segmentation: splits by meaning, not silence.
            // Produces more complete utterances with better speaker attribution
            // vs Default (silence-based) which can split mid-sentence and misattribute speakers.
            setProperty(PropertyId.Speech_SegmentationStrategy, "Semantic")
            setProfanity(ProfanityOption.Raw)
        }

    private fun seedPhraseList(phraseListGrammar: PhraseListGrammar, contextNotes: String) {
        if (contextNotes.isNotBlank()) {
            contextNotes.split(",", "\n", ";")
                .map { it.trim() }.filter { it.isNotBlank() }
                .forEach { phraseListGrammar.addPhrase(it) }
            VoiceLog.log("SVC", "PhraseList: ${contextNotes.take(80)}")
        }
    }

    /** Shared by both recognizer types — feeds the debounced, accumulated transcript. */
    private fun onUtteranceRecognized(rawSpeakerId: String?, text: String) {
        if (text.isBlank()) return
        val speakerId = normalizeSpeakerId(rawSpeakerId ?: "Unknown")
        synchronized(debounceLock) {
            pendingBySpeaker[speakerId] = text
            debounceJobBySpeaker[speakerId]?.cancel()
            debounceJobBySpeaker[speakerId] = serviceScope.launch {
                delay(300)
                val finalText = synchronized(debounceLock) { pendingBySpeaker.remove(speakerId) }
                if (finalText != null) {
                    VoiceLog.log("SVC", "accepting [$speakerId]: ${finalText.take(60)}")
                    val entry = TranscriptEntry(timeFormat.format(Date()), speakerId, finalText)
                    _transcriptEntries.update { current ->
                        val updated = current + entry
                        transcriptStore.save(updated)
                        updated
                    }
                }
            }
        }
    }

    private fun onRecognitionCanceled(errorDetails: String) {
        VoiceLog.log("SVC", "canceled: $errorDetails")
        serviceScope.launch {
            error.emit("Transcription canceled: $errorDetails")
            isRecording.update { false }
        }
    }

    private suspend fun startTranscribing(
        azureKey: String,
        azureRegion: String,
        contextNotes: String,
        mode: DictationMode,
    ) {
        try {
            val speechConfig = buildSpeechConfig(azureKey, azureRegion)
            val autoDetectConfig = AutoDetectSourceLanguageConfig.fromLanguages(
                listOf("es-US", "en-US")
            )
            val audioConfig = AudioConfig.fromDefaultMicrophoneInput()

            when (mode) {
                DictationMode.CONVERSATION -> {
                    val ct = ConversationTranscriber(speechConfig, autoDetectConfig, audioConfig)
                    conversationTranscriber = ct
                    seedPhraseList(PhraseListGrammar.fromRecognizer(ct), contextNotes)

                    ct.transcribed.addEventListener { _, e ->
                        val r = e.result
                        VoiceLog.log("SVC", "transcribed event: reason=${r.reason} speakerId=${r.speakerId} text=${r.text.take(80)}")
                        // Only process fully recognized speech — skip NoMatch, Canceled, partials
                        if (r.reason != ResultReason.RecognizedSpeech) {
                            VoiceLog.log("SVC", "SKIPPED reason=${r.reason}")
                            return@addEventListener
                        }
                        onUtteranceRecognized(r.speakerId, r.text)
                    }
                    ct.canceled.addEventListener { _, e -> onRecognitionCanceled(e.errorDetails) }

                    wakeLock = acquireWakeLock()
                    ct.startTranscribingAsync().get()
                }
                DictationMode.NOTES -> {
                    val sr = SpeechRecognizer(speechConfig, autoDetectConfig, audioConfig)
                    speechRecognizer = sr
                    seedPhraseList(PhraseListGrammar.fromRecognizer(sr), contextNotes)

                    sr.recognized.addEventListener { _, e ->
                        val r = e.result
                        VoiceLog.log("SVC", "recognized event: reason=${r.reason} text=${r.text.take(80)}")
                        if (r.reason != ResultReason.RecognizedSpeech) {
                            VoiceLog.log("SVC", "SKIPPED reason=${r.reason}")
                            return@addEventListener
                        }
                        onUtteranceRecognized(NOTES_SPEAKER_ID, r.text)
                    }
                    sr.canceled.addEventListener { _, e -> onRecognitionCanceled(e.errorDetails) }

                    wakeLock = acquireWakeLock()
                    sr.startContinuousRecognitionAsync().get()
                }
            }

            isRecording.update { true }
            VoiceLog.log("SVC", "transcription started, mode=$mode, autodetect: es-US,en-US")
        } catch (e: Exception) {
            VoiceLog.log("SVC", "start error: ${e.message}")
            error.emit(e.message ?: "Failed to start")
            isRecording.update { false }
            stopSelf()
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock {
        VoiceLog.log("SVC", "WakeLock acquired")
        return (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NablaVoice::TranscriptionLock")
            .also { it.acquire(4 * 60 * 60 * 1000L) } // max 4h timeout safety net
    }

    /**
     * Wipes the accumulated transcript (user-initiated "clear", independent of stopping the
     * session). Must be called on the service, not just a ViewModel's mirror: transcriptEntries
     * is the cumulative source of truth, so clearing only a mirror would get silently
     * overwritten back to the pre-clear list on the next accepted utterance.
     */
    fun clearTranscript() {
        _transcriptEntries.value = emptyList()
    }

    fun stopTranscription() {
        stopRequested = true
        serviceScope.launch {
            try {
                conversationTranscriber?.stopTranscribingAsync()?.get()
                speechRecognizer?.stopContinuousRecognitionAsync()?.get()
            } catch (e: Exception) {
                VoiceLog.log("SVC", "stop error: ${e.message}")
            } finally {
                cleanup()
            }
        }
    }

    private fun cleanup() {
        synchronized(debounceLock) {
            debounceJobBySpeaker.values.forEach { it.cancel() }
            debounceJobBySpeaker.clear()
            pendingBySpeaker.clear()
        }
        try { conversationTranscriber?.close() } catch (_: Exception) {}
        try { speechRecognizer?.close() } catch (_: Exception) {}
        conversationTranscriber = null
        speechRecognizer = null
        isRecording.update { false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            VoiceLog.log("SVC", "WakeLock release error: ${e.message}")
        }
        wakeLock = null
        VoiceLog.log("SVC", "WakeLock released")
        VoiceLog.log("SVC", "cleanup: calling stopSelf()")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active transcription session" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentIntent: PendingIntent?): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcribing…")
            .setContentText("Dictation session in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .setOngoing(true)
            .build()
    }

    private fun normalizeSpeakerId(raw: String): String {
        if (raw == "Unknown" || raw.isBlank()) return "Unknown"
        val match = Regex("(?i)guest[-_]?(\\d+)").find(raw)
        return if (match != null) "GUEST_${match.groupValues[1]}" else raw
    }

    @Suppress("DEPRECATION")
    private fun extractContentIntent(intent: Intent?): PendingIntent? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT, PendingIntent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT)
        }
    }
}
