package com.nabla.notes.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Keeps the soft keyboard out of the way while dictating: hides it (and drops text focus) any
 * time it is up during [recording], including when the user taps a field mid-session. If it was
 * showing when dictation started, [onRestore] runs and the keyboard is shown again when
 * dictation stops — [onRestore] should re-focus whichever field the user was typing in.
 */
@Composable
fun DictationKeyboardControl(recording: Boolean, onRestore: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var restoreOnStop by remember { mutableStateOf(false) }
    var wasRecording by remember { mutableStateOf(false) }

    LaunchedEffect(recording) {
        if (recording && !wasRecording) {
            restoreOnStop = imeVisible
        } else if (!recording && wasRecording && restoreOnStop) {
            restoreOnStop = false
            onRestore()
            keyboard?.show()
        }
        wasRecording = recording
    }

    LaunchedEffect(recording, imeVisible) {
        if (recording && imeVisible) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
}
