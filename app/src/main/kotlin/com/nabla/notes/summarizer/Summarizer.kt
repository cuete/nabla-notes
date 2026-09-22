package com.nabla.notes.summarizer

import com.nabla.voice.TranscriptEntry

/**
 * Provider-agnostic seam for summarizing a dictation session — the thing the Summary tab's
 * "Summarize" button was built for (P3/P4) but stayed disabled until this existed.
 *
 * Deliberately not chato's organizeNotes()/summarize(), which called its OpenClaw gateway
 * directly with a hardcoded prompt baked into the ViewModel. Splitting the provider out behind
 * this interface is the whole point of this phase: OpenClawSummarizer is the only
 * implementation today, but a Claude-API-direct implementation (or anything else) is a new
 * class + one Hilt @Binds swap, not a rewrite of DictationViewModel.
 *
 * transcript and notes are passed separately, not pre-merged into one string — matches the
 * ViewModel's own transcriptEntries/typedNotes split (2026-09-16 device-testing feedback:
 * "those notes are to be sent along with the transcript payload for summarization"). Each
 * implementation decides how to combine them into a prompt.
 */
interface Summarizer {
    suspend fun summarize(transcript: List<TranscriptEntry>, notes: String): Result<String>

    /** AI cleanup/reorganization of a note's markdown; returns the full reorganized text. */
    suspend fun organize(content: String): Result<String>
}
