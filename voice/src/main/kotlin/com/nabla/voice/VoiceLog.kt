package com.nabla.voice

/**
 * Minimal logcat-only logging for this module. Deliberately not the host app's own logger
 * (e.g. a file-backed debug-log screen) — this module doesn't know what app it's running in,
 * so it can't depend on app-level infrastructure. Wrap/forward to a fuller logger from the
 * host app's own code if needed; TranscriptionService only needs logcat visibility.
 */
internal object VoiceLog {
    private const val TAG = "NablaVoice"

    fun log(component: String, msg: String) {
        android.util.Log.d(TAG, "$component: $msg")
    }
}
