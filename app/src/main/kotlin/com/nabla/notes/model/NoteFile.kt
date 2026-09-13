package com.nabla.notes.model

/** Broad category of a file, used to route file-browser taps to the right viewer. */
enum class FileKind { MARKDOWN, TEXT, IMAGE, PDF, OTHER }

/**
 * Represents a note file stored on OneDrive.
 *
 * @param id                OneDrive item ID
 * @param name              File name (including extension)
 * @param lastModified      ISO-8601 timestamp string from Graph API
 * @param mimeType          Graph `file.mimeType`, when known
 * @param downloadUrl       Graph `@microsoft.graph.downloadUrl` — pre-signed, short-lived
 * @param parentFolderId    OneDrive item ID of the containing folder
 * @param parentPath        OneDrive-root-relative path of the containing folder (e.g. "Notes/trip", or "" for root)
 */
data class NoteFile(
    val id: String,
    val name: String,
    val lastModified: String = "",
    val mimeType: String? = null,
    val downloadUrl: String? = null,
    val parentFolderId: String? = null,
    val parentPath: String? = null
) {
    val isMarkdown: Boolean
        get() = name.endsWith(".md", ignoreCase = true)

    val kind: FileKind
        get() = when {
            name.endsWith(".md", ignoreCase = true) -> FileKind.MARKDOWN
            name.endsWith(".txt", ignoreCase = true) -> FileKind.TEXT
            name.endsWith(".pdf", ignoreCase = true) || mimeType == "application/pdf" -> FileKind.PDF
            IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } ||
                mimeType?.startsWith("image/") == true -> FileKind.IMAGE
            else -> FileKind.OTHER
        }

    val displayName: String
        get() = name.removeSuffix(".md").removeSuffix(".txt")

    companion object {
        val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")
    }
}

/**
 * Represents an OneDrive folder shown in the file browser.
 *
 * @param id    OneDrive item ID
 * @param name  Folder display name
 */
data class FolderItem(
    val id: String,
    val name: String
)

/**
 * Combined browser entry — either a folder or a note file.
 * Used to display a mixed list in [com.nabla.notes.ui.browser.FileBrowserScreen].
 */
sealed class BrowserEntry {
    data class Folder(val item: FolderItem) : BrowserEntry()
    data class File(val note: NoteFile) : BrowserEntry()

    val displayName: String
        get() = when (this) {
            is Folder -> item.name
            is File -> note.name
        }
}
