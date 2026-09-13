package com.nabla.notes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PdfRenderer only allows one open Page at a time and is not thread-safe,
 * so every access is serialized through [mutex].
 */
class PdfRendererDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    suspend fun open(uri: Uri): Int = mutex.withLock {
        closeLocked()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open file descriptor for $uri")
        val pdfRenderer = PdfRenderer(pfd)
        fileDescriptor = pfd
        renderer = pdfRenderer
        pdfRenderer.pageCount
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap =
        mutex.withLock { renderPageLocked(pageIndex, targetWidthPx) }

    fun close() {
        closeLocked()
    }

    private fun renderPageLocked(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val pdfRenderer = checkNotNull(renderer) { "Document is not open" }
        pdfRenderer.openPage(pageIndex).use { page ->
            val scale = targetWidthPx.toFloat() / page.width
            val targetHeightPx = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private fun closeLocked() {
        renderer?.close()
        fileDescriptor?.close()
        renderer = null
        fileDescriptor = null
    }
}
