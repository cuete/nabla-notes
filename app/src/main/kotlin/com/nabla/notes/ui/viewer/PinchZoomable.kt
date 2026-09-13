package com.nabla.notes.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Reacts to multi-touch (pinch) events always, and to single-finger drags only once
 * already zoomed in — so at 1x the enclosing LazyColumn keeps handling normal page
 * scroll, but zoomed in you can pan around (including left-right) with one finger.
 */
fun Modifier.pinchZoomable(
    zoomState: MutableState<ZoomState>,
    minScale: Float = 1f,
    maxScale: Float = 5f,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            // Initial pass: run before LazyColumn's own scrollable (inner in the modifier
            // chain) claims single-finger drags for normal list scroll on the Main pass.
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val isMultiTouch = event.changes.size >= 2
            val isPanningWhileZoomed = zoomState.value.scale > minScale
            if (isMultiTouch || isPanningWhileZoomed) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                zoomState.value = applyPinchGesture(zoomState.value, zoomChange, panChange, minScale, maxScale)
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
