package com.nabla.voice

/** A single transcribed utterance. speakerId is "You" for Notes-mode (single-speaker) entries. */
data class TranscriptEntry(
    val timestamp: String,   // HH:mm:ss
    val speakerId: String,   // GUEST_1, GUEST_2, etc., "You", or "Unknown"
    val text: String,
)
