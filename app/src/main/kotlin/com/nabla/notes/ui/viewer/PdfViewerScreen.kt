package com.nabla.notes.ui.viewer

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nabla.notes.model.NoteFile
import com.nabla.notes.viewmodel.PdfViewerUiState
import com.nabla.notes.viewmodel.PdfViewerViewModel

/** Extra resolution headroom so pages stay reasonably sharp when pinch-zoomed in. */
private const val RENDER_SCALE_MULTIPLIER = 2
private const val PLACEHOLDER_ASPECT_RATIO = 0.75f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    noteFile: NoteFile,
    onBackClick: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel()
) {
    val activity = LocalContext.current as Activity
    LaunchedEffect(noteFile.id) { viewModel.loadDocument(noteFile.id, activity) }
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is PdfViewerUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is PdfViewerUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
                is PdfViewerUiState.Ready -> PdfPages(viewModel, state.pageCount)
            }
        }
    }
}

@Composable
private fun PdfPages(viewModel: PdfViewerViewModel, pageCount: Int) {
    val zoomState = remember { mutableStateOf(ZoomState.Default) }
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
        val renderWidthPx = with(LocalDensity.current) {
            (maxWidth.toPx() * RENDER_SCALE_MULTIPLIER).toInt().coerceAtLeast(1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pinchZoomable(zoomState)
                .graphicsLayer {
                    scaleX = zoomState.value.scale
                    scaleY = zoomState.value.scale
                    translationX = zoomState.value.offset.x
                    translationY = zoomState.value.offset.y
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(count = pageCount, key = { it }) { index ->
                PdfPageItem(viewModel = viewModel, index = index, renderWidthPx = renderWidthPx)
            }
        }
    }
}

@Composable
private fun PdfPageItem(viewModel: PdfViewerViewModel, index: Int, renderWidthPx: Int) {
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, index, renderWidthPx) {
        value = viewModel.renderPage(index, renderWidthPx)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: PLACEHOLDER_ASPECT_RATIO)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator()
        }
    }
}
