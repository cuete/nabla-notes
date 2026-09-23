package com.nabla.notes.markdown

import android.app.Activity
import android.util.Base64
import android.util.Log
import com.nabla.notes.model.DriveItemMeta
import com.nabla.notes.model.FileKind
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository

private const val TAG = "NablaNotes"

/** Matches `![alt](dest)` — captures alt text and dest separately. */
private val MARKDOWN_IMAGE_REGEX = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

/** Matches `[text](dest)`, excluding image syntax (`![...]`) which MARKDOWN_IMAGE_REGEX already covers. */
private val MARKDOWN_LINK_REGEX = Regex("""(?<!!)\[([^\]]*)]\(([^)\s]+)\)""")

private fun kindOf(name: String, mimeType: String?): FileKind = NoteFile(id = "", name = name, mimeType = mimeType).kind

/**
 * Rewrites relative-path `![alt](dest)`/`[text](dest)` references in [content] so they resolve
 * against OneDrive:
 *  - image syntax → dest becomes a `data:` URI embedding a downsized preview thumbnail (fetched via
 *    the same authenticated [OneDriveRepository.downloadFileBytes] path the full-screen image/PDF
 *    viewers already use successfully). This deliberately avoids depending on Graph's
 *    `@microsoft.graph.downloadUrl` — a pre-signed URL that GlideImagesPlugin would otherwise
 *    fetch unauthenticated and that proved unreliable in practice. Glide loads `data:` URIs
 *    natively, so no image-loading plugin changes are needed. The image is additionally wrapped in
 *    a synthetic `nablanote://` link to its own file id, so tapping the inline (thumbnail-quality)
 *    preview opens the existing full-screen [com.nabla.notes.ui.viewer.ImageViewerScreen], which
 *    downloads full-resolution bytes — reusing the same viewer/link-tap plumbing as note-to-note
 *    links instead of building a second image viewer.
 *  - link syntax → dest becomes a synthetic `nablanote://` URI (see [buildNablaLinkUri]),
 *    intercepted at tap-time by [relativeLinkResolverPlugin] to open the in-app viewer
 *
 * Absolute/external dests (http(s)://, mailto:, already-resolved nablanote://) are left untouched.
 * Dests that fail to resolve (not found, offline) are also left untouched — same broken-link
 * behavior as today, no regression.
 */
suspend fun resolveRelativeMediaLinks(
    content: String,
    parentPath: String,
    oneDriveRepository: OneDriveRepository,
    activity: Activity
): String {
    suspend fun resolveMeta(dest: String): DriveItemMeta? {
        val absolutePath = resolveRelativePath(parentPath, dest)
        return oneDriveRepository.resolveItemByPath(absolutePath, activity).fold(
            onSuccess = { meta ->
                Log.d(TAG, "resolveRelativeMediaLinks: '$dest' -> '$absolutePath' resolved (id=${meta.id})")
                meta
            },
            onFailure = { e ->
                Log.w(TAG, "resolveRelativeMediaLinks: '$dest' -> '$absolutePath' failed: ${e.message}")
                null
            }
        )
    }

    val imageDests = MARKDOWN_IMAGE_REGEX.findAll(content)
        .map { it.destructured.component2() }
        .filterNot { isExternalOrAbsolute(it) }
        .toSet()
    val linkDests = MARKDOWN_LINK_REGEX.findAll(content)
        .map { it.destructured.component2() }
        .filterNot { isExternalOrAbsolute(it) }
        .toSet()

    // Images need actual bytes (embedded as a data: URI) plus their file id (to link to the
    // full-resolution viewer); links only need id/kind/name to navigate.
    // A "large" (~800px) server-generated thumbnail is plenty for inline preview and is far
    // smaller/faster than the full original — fall back to full bytes if no thumbnail exists yet.
    data class ResolvedImage(val dataUri: String, val id: String, val name: String)

    val imageResolved = imageDests.associateWith { dest ->
        val meta = resolveMeta(dest) ?: return@associateWith null
        val thumbnailResult = oneDriveRepository.downloadThumbnailBytes(meta.id, "large", activity)
        val bytesResult = if (thumbnailResult.isSuccess) thumbnailResult
            else oneDriveRepository.downloadFileBytes(meta.id, activity)
        bytesResult.fold(
            onSuccess = { bytes ->
                val mimeType = if (thumbnailResult.isSuccess) "image/jpeg" else (meta.mimeType ?: "image/jpeg")
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ResolvedImage("data:$mimeType;base64,$encoded", meta.id, meta.name)
            },
            onFailure = { e ->
                Log.w(TAG, "resolveRelativeMediaLinks: failed to download bytes for '$dest': ${e.message}")
                null
            }
        )
    }
    val resolvedLinks = linkDests.associateWith { dest -> resolveMeta(dest) }

    var result = MARKDOWN_IMAGE_REGEX.replace(content) { match ->
        val (alt, dest) = match.destructured
        val image = imageResolved[dest]
        if (image != null) {
            val zoomUri = buildNablaLinkUri(image.id, FileKind.IMAGE, image.name)
            "[![$alt](${image.dataUri})]($zoomUri)"
        } else {
            match.value
        }
    }

    result = MARKDOWN_LINK_REGEX.replace(result) { match ->
        val (text, dest) = match.destructured
        val meta = resolvedLinks[dest]
        if (meta != null) {
            val uri = buildNablaLinkUri(meta.id, kindOf(meta.name, meta.mimeType), meta.name)
            "[$text]($uri)"
        } else {
            match.value
        }
    }

    return result
}
