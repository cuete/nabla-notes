package com.nabla.notes.ui.viewer

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.nabla.notes.model.NoteFile
import com.nabla.notes.ui.common.ZoomableImage
import com.nabla.notes.viewmodel.ImageViewerUiState
import com.nabla.notes.viewmodel.ImageViewerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    noteFile: NoteFile,
    onBackClick: () -> Unit,
    viewModel: ImageViewerViewModel = hiltViewModel()
) {
    val activity = LocalContext.current as Activity
    LaunchedEffect(noteFile.id) { viewModel.loadImage(noteFile.id, activity) }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(noteFile.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (val state = uiState) {
                is ImageViewerUiState.Loading -> CircularProgressIndicator()
                is ImageViewerUiState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )
                is ImageViewerUiState.Ready -> ZoomableImage(
                    bitmap = state.bitmap,
                    contentDescription = noteFile.name,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
