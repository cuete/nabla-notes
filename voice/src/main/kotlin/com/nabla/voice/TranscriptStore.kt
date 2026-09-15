package com.nabla.voice

/**
 * Durable persistence for the accumulated transcript, implemented by whichever app hosts
 * this module (SharedPreferences, DataStore, a file — TranscriptionService doesn't care).
 *
 * This is the seam that lets TranscriptionService own the session (see its class doc) without
 * this module knowing anything about a specific app's storage layer. The host app provides a
 * Hilt @Binds for this interface; TranscriptionService just injects TranscriptStore.
 *
 * Calls are synchronous and expected to be cheap (a SharedPreferences-backed implementation is
 * cheap; anything slower should do its own internal buffering/dispatching, since the service
 * calls save() on its own single-threaded debounce completion, not off a background dispatcher).
 */
interface TranscriptStore {
    fun load(): List<TranscriptEntry>
    fun save(entries: List<TranscriptEntry>)
}
