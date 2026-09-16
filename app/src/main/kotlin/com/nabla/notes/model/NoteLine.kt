package com.nabla.notes.model

/**
 * One line in the unified dictation stream — replaces the old plain-string pendingText, which
 * carried spoken and typed entries as 🎙/📝-prefixed text with no per-entry timestamp. Added
 * 2026-09-15 (device-testing feedback: the separate transcript view and pending-notes preview
 * showed the same content twice while purely dictating, and merging them dropped the
 * timestamp/speaker info the transcript view had).
 *
 * [speakerId] is null for typed entries — there's no speaker to diarize, the user just typed.
 * [precededByGap] marks a pause of DictationViewModel.PENDING_LINE_BREAK_GAP_MS or more before
 * this entry, so the UI can render a visual break — same gap concept the old string-based
 * PENDING_LINE_BREAK_GAP_MS logic had, translated from "insert a blank line in the string" to
 * a per-entry flag now that entries are structured instead of flattened into one string.
 */
data class NoteLine(
    val timestamp: String,
    val speakerId: String?,
    val text: String,
    val typed: Boolean,
    val precededByGap: Boolean = false,
)
