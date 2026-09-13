package com.nabla.notes.ui.viewer

import androidx.compose.ui.geometry.Offset

data class ZoomState(val scale: Float, val offset: Offset) {
    companion object {
        val Default = ZoomState(scale = 1f, offset = Offset.Zero)
    }
}

/**
 * Pure reducer for a pinch/pan gesture step. Offset resets to zero once the
 * content returns to its un-zoomed scale, so it never gets stuck panned
 * off-screen after zooming back out.
 */
fun applyPinchGesture(
    current: ZoomState,
    zoomChange: Float,
    panChange: Offset,
    minScale: Float = 1f,
    maxScale: Float = 5f,
): ZoomState {
    val newScale = (current.scale * zoomChange).coerceIn(minScale, maxScale)
    val newOffset = if (newScale > minScale) current.offset + panChange else Offset.Zero
    return ZoomState(newScale, newOffset)
}
