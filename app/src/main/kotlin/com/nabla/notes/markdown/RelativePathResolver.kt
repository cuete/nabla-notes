package com.nabla.notes.markdown

/** True for links/images that already point somewhere absolute — nothing to resolve. */
fun isExternalOrAbsolute(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
        path.startsWith("https://", ignoreCase = true) ||
        path.startsWith("mailto:", ignoreCase = true) ||
        path.startsWith("nablanote://", ignoreCase = true)

/**
 * Resolves [relative] (as written in markdown — e.g. "./photo.png", "photos/img.jpg",
 * "../shared/x.png") against [basePath] (the current note's parent folder path, e.g.
 * "Notes/trip", or "" for root). A [relative] path starting with "/" is treated as already
 * OneDrive-root-relative and is normalized as-is.
 *
 * Returns a normalized OneDrive-root-relative path with no leading slash, with "." and ".."
 * segments collapsed.
 */
fun resolveRelativePath(basePath: String, relative: String): String {
    val baseSegments = if (relative.startsWith("/")) {
        emptyList()
    } else {
        basePath.split("/").filter { it.isNotBlank() }
    }
    val relativeSegments = relative.trim('/').split("/").filter { it.isNotEmpty() }

    val stack = ArrayDeque(baseSegments)
    for (segment in relativeSegments) {
        when (segment) {
            "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(segment)
        }
    }
    return stack.joinToString("/")
}
