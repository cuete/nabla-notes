package com.nabla.notes.summarizer

import com.nabla.notes.repository.DictationRepository
import com.nabla.voice.TranscriptEntry
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only buildPrompt's content — summarize()'s HTTP call is exercised indirectly via
 * DictationViewModelTest against the Summarizer interface, not against this real
 * implementation's networking.
 */
class OpenClawSummarizerTest {

    private val summarizer = OpenClawSummarizer(mockk<DictationRepository>(relaxed = true), mockk<OkHttpClient>(relaxed = true))

    @Test
    fun `prompt instructs the model to match the content's language, not always Spanish`() {
        // 2026-09-16 feedback: summaries always came back in Spanish even for English input —
        // the instruction itself is phrased in Spanish, which isn't the same as asking the
        // model to *respond* in Spanish, but without an explicit line saying so the model
        // defaulted to matching the instruction's own language instead of the content's.
        val prompt = summarizer.buildPrompt(
            transcript = listOf(TranscriptEntry("10:00:00", "You", "This is an English transcript.")),
            notes = "",
        )

        assertTrue(prompt.contains("mismo idioma"))
        assertTrue(prompt.contains("No traduzcas"))
    }

    @Test
    fun `prompt includes notes only when present`() {
        val withoutNotes = summarizer.buildPrompt(transcript = emptyList(), notes = "")
        val withNotes = summarizer.buildPrompt(transcript = emptyList(), notes = "remember to follow up")

        assertTrue(!withoutNotes.contains("remember to follow up"))
        assertTrue(withNotes.contains("remember to follow up"))
    }
}
