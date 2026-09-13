package com.nabla.notes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Application class — required for Hilt dependency injection.
 */
@HiltAndroidApp
class NotepadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Defensively clear any images/PDFs cached from a previous process that got killed
        // before its viewer's onCleared() could delete them (see PdfViewerViewModel).
        File(cacheDir, "onedrive_media").deleteRecursively()
    }
}
