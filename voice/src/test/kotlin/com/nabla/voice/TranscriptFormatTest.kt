package com.nabla.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatTest {

    @Test
    fun `notes entries from one session collapse into a single timestamped block`() {
        val entries = listOf(
            TranscriptEntry("10:00:00", NOTES_SPEAKER_ID, "first"),
            TranscriptEntry("10:00:00", NOTES_SPEAKER_ID, "second"),
            TranscriptEntry("10:00:00", NOTES_SPEAKER_ID, "third"),
        )
        assertEquals(listOf(TranscriptBlock("10:00:00", NOTES_SPEAKER_ID, "first second third")), groupTranscript(entries))
    }

    @Test
    fun `separate notes sessions keep separate timestamps`() {
        val entries = listOf(
            TranscriptEntry("10:00:00", NOTES_SPEAKER_ID, "a"),
            TranscriptEntry("11:30:00", NOTES_SPEAKER_ID, "b"),
        )
        assertEquals("[10:00:00] You: a\n[11:30:00] You: b", formatTranscript(entries))
    }

    @Test
    fun `conversation entries keep per-utterance timestamps`() {
        val entries = listOf(
            TranscriptEntry("10:00:01", "GUEST_1", "hi"),
            TranscriptEntry("10:00:02", "GUEST_1", "there"),
            TranscriptEntry("10:00:02", "GUEST_2", "yo"),
        )
        assertEquals(3, groupTranscript(entries).size)
        assertEquals("[10:00:01] GUEST_1: hi\n[10:00:02] GUEST_1: there\n[10:00:02] GUEST_2: yo", formatTranscript(entries))
    }
}
