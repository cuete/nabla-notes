package com.nabla.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from nabla-chato-voice unchanged — the debounce logic itself didn't move (it's
 * still TranscriptionService.onUtteranceRecognized), only where its output goes.
 */
class TranscriptDebounceTest {

    /**
     * Simulates the debounce logic from TranscriptionService:
     * multiple utterances within 300ms should only emit the last one.
     */
    @Test
    fun `debounce emits only last utterance within window`() = runTest {
        val emitted = mutableListOf<String>()
        var pendingText: String? = null
        var debounceJob: Job? = null

        suspend fun onUtterance(text: String) {
            pendingText = text
            debounceJob?.cancel()
            debounceJob = launch {
                delay(300)
                pendingText?.let { emitted.add(it) }
                pendingText = null
            }
        }

        // Simulate Azure: 3 utterances within 100ms window
        onUtterance("Hello this is")
        delay(50)
        onUtterance("Hello this is a test")
        delay(50)
        onUtterance("Hello this is a test 123.")  // consolidated final

        // Wait for debounce to fire
        delay(400)

        assertEquals(1, emitted.size)
        assertEquals("Hello this is a test 123.", emitted[0])
    }

    @Test
    fun `two separate utterances spaced apart both emit`() = runTest {
        val emitted = mutableListOf<String>()
        var pendingText: String? = null
        var debounceJob: Job? = null

        suspend fun onUtterance(text: String) {
            pendingText = text
            debounceJob?.cancel()
            debounceJob = launch {
                delay(300)
                pendingText?.let { emitted.add(it) }
                pendingText = null
            }
        }

        onUtterance("First sentence.")
        delay(500)  // outside debounce window
        onUtterance("Second sentence.")
        delay(400)

        assertEquals(2, emitted.size)
        assertEquals("First sentence.", emitted[0])
        assertEquals("Second sentence.", emitted[1])
    }

    @Test
    fun `different speakers within window both emit independently`() = runTest {
        // Regression test: global debounce dropped utterances from other speakers.
        // Per-speaker debounce must let GUEST_1 and GUEST_2 emit independently.
        data class Utterance(val speakerId: String, val text: String)
        val emitted = mutableListOf<Utterance>()

        val pendingBySpeaker = mutableMapOf<String, String>()
        val jobBySpeaker = mutableMapOf<String, Job>()

        suspend fun onUtterance(speakerId: String, text: String) {
            pendingBySpeaker[speakerId] = text
            jobBySpeaker[speakerId]?.cancel()
            jobBySpeaker[speakerId] = launch {
                delay(300)
                val t = pendingBySpeaker.remove(speakerId)
                if (t != null) emitted.add(Utterance(speakerId, t))
            }
        }

        // Azure burst: GUEST_1 then GUEST_2 within 100ms, then GUEST_1 consolidated
        onUtterance("GUEST_1", "Hello this is")
        delay(50)
        onUtterance("GUEST_2", "Just a test.")
        delay(50)
        onUtterance("GUEST_1", "Hello this is a test 123.")  // replaces earlier GUEST_1

        delay(400)

        assertEquals(2, emitted.size)
        // GUEST_2 emits fully (not dropped by GUEST_1 debounce)
        assertTrue(emitted.any { it.speakerId == "GUEST_2" && it.text == "Just a test." })
        // GUEST_1 emits the consolidated final
        assertTrue(emitted.any { it.speakerId == "GUEST_1" && it.text == "Hello this is a test 123." })
    }
}
