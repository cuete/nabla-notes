package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.pdf.PdfRendererDataSource
import com.nabla.notes.repository.OneDriveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class PdfViewerUiState {
    object Loading : PdfViewerUiState()
    data class Ready(val pageCount: Int) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfRendererDataSource: PdfRendererDataSource,
    private val oneDriveRepository: OneDriveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var cachedFile: File? = null

    fun loadDocument(fileId: String, activity: Activity) {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            oneDriveRepository.downloadFileBytes(fileId, activity).fold(
                onSuccess = { bytes ->
                    try {
                        val dir = File(context.cacheDir, "onedrive_media").apply { mkdirs() }
                        val file = File(dir, "$fileId.pdf").apply { writeBytes(bytes) }
                        cachedFile = file
                        val pageCount = pdfRendererDataSource.open(Uri.fromFile(file))
                        _uiState.value = PdfViewerUiState.Ready(pageCount)
                    } catch (e: Exception) {
                        _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to open PDF")
                    }
                },
                onFailure = { e ->
                    _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to load PDF")
                }
            )
        }
    }

    suspend fun renderPage(index: Int, widthPx: Int): Bitmap = pdfRendererDataSource.renderPage(index, widthPx)

    override fun onCleared() {
        pdfRendererDataSource.close()
        cachedFile?.delete()
        cachedFile = null
    }
}
