package com.nabla.voice

/** One displayed transcript line: a timestamp/speaker header plus the text under it. */
data class TranscriptBlock(val timestamp: String, val speakerId: String, val text: String)

/**
 * Notes-mode entries share their session's start timestamp (see TranscriptionService), so
 * consecutive entries with the same timestamp merge into one block — one stamp per session, not
 * per utterance. Conversation entries stay one block each.
 */
fun groupTranscript(entries: List<TranscriptEntry>): List<TranscriptBlock> {
    val blocks = ArrayList<TranscriptBlock>()
    for (e in entries) {
        val last = blocks.lastOrNull()
        if (last != null && e.speakerId == NOTES_SPEAKER_ID &&
            last.speakerId == NOTES_SPEAKER_ID && last.timestamp == e.timestamp
        ) {
            blocks[blocks.lastIndex] = last.copy(text = last.text + " " + e.text)
        } else {
            blocks.add(TranscriptBlock(e.timestamp, e.speakerId, e.text))
        }
    }
    return blocks
}

fun formatTranscript(entries: List<TranscriptEntry>): String =
    groupTranscript(entries).joinToString("\n") { "[${it.timestamp}] ${it.speakerId}: ${it.text}" }
