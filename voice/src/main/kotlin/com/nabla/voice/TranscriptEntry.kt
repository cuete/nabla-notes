package com.nabla.voice

/** speakerId of every Notes-mode (single-speaker) entry. */
const val NOTES_SPEAKER_ID = "You"

/** A single transcribed utterance. speakerId is [NOTES_SPEAKER_ID] for Notes-mode entries. */
data class TranscriptEntry(
    val timestamp: String,   // yyyy-MM-dd HH:mm:ss (entries saved before 2026-09-21 are HH:mm:ss only)
    val speakerId: String,   // GUEST_1, GUEST_2, etc., "You", or "Unknown"
    val text: String,
)
