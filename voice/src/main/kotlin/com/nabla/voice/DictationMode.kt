package com.nabla.voice

enum class DictationMode {
    /** Diarized, multi-speaker transcription — GUEST_1, GUEST_2, etc. */
    CONVERSATION,

    /** Single-speaker continuous recognition, attributed to a constant "You". */
    NOTES;

    companion object {
        fun fromExtra(value: String?): DictationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CONVERSATION
    }
}
