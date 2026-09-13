package com.nabla.notes.viewmodel

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.repository.OneDriveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class ImageViewerUiState {
    object Loading : ImageViewerUiState()
    data class Ready(val bitmap: Bitmap) : ImageViewerUiState()
    data class Error(val message: String) : ImageViewerUiState()
}

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val oneDriveRepository: OneDriveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImageViewerUiState>(ImageViewerUiState.Loading)
    val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

    fun loadImage(fileId: String, activity: Activity) {
        viewModelScope.launch {
            _uiState.value = ImageViewerUiState.Loading
            oneDriveRepository.downloadFileBytes(fileId, activity).fold(
                onSuccess = { bytes ->
                    val bitmap = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    _uiState.value = if (bitmap != null) {
                        ImageViewerUiState.Ready(bitmap)
                    } else {
                        ImageViewerUiState.Error("Could not decode image")
                    }
                },
                onFailure = { e ->
                    _uiState.value = ImageViewerUiState.Error(e.message ?: "Failed to load image")
                }
            )
        }
    }
}
