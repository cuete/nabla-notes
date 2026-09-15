package com.nabla.voice

/**
 * Durable persistence for the accumulated transcript, implemented by whichever app hosts
 * this module (SharedPreferences, DataStore, a file — TranscriptionService doesn't care).
 *
 * This is the seam that lets TranscriptionService own the session (see its class doc) without
 * this module knowing anything about a specific app's storage layer. The host app provides a
 * Hilt @Binds for this interface; TranscriptionService just injects TranscriptStore.
 *
 * suspend rather than blocking: the obvious host storage on modern Android (DataStore) is
 * suspend-only, and TranscriptionService only ever calls these from inside its own
 * serviceScope coroutines anyway (the debounce completion, and onCreate's initial load), so
 * there's no synchronous-caller constraint forcing the alternative.
 */
interface TranscriptStore {
    suspend fun load(): List<TranscriptEntry>
    suspend fun save(entries: List<TranscriptEntry>)
}
