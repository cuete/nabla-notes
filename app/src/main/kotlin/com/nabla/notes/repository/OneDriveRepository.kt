package com.nabla.notes.repository

import android.app.Activity
import android.util.Log
import com.nabla.notes.auth.MsalManager
import com.nabla.notes.model.BrowserEntry
import com.nabla.notes.model.DriveItemMeta
import com.nabla.notes.model.FolderItem
import com.nabla.notes.model.NoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Microsoft OneDrive operations via Microsoft Graph API.
 *
 * All API calls go through the Graph REST endpoint using OkHttp.
 * Authentication tokens are managed by [MsalManager].
 *
 * Endpoints used:
 *  - GET  /me/drive/root/children?$select=id,name,lastModifiedDateTime  — list folder contents
 *  - GET  /me/drive/items/{id}/children?$select=...                     — list subfolder
 *  - GET  /me/drive/items/{id}/content                                  — download file text
 *  - PUT  /me/drive/items/{id}/content                                  — overwrite file
 *  - PUT  /me/drive/root:/{path}:/content                               — create new file
 *  - GET  /me/drive/root/children?$filter=folder ne null                — list folders
 */
@Singleton
class OneDriveRepository @Inject constructor(
    private val msalManager: MsalManager,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "OneDriveRepository"
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"

        // Text media type for file content uploads
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

        // JSON media type for PATCH requests (move/rename)
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }

    // ─── File listing ───────────────────────────────────────────────────────────

    /**
     * List .txt and .md files in the configured OneDrive folder.
     *
     * @param folderId  OneDrive item ID, or "root" for the drive root.
     * @param activity  Required for interactive auth fallback.
     * @return List of [NoteFile] sorted by name.
     */
    suspend fun listNoteFiles(
        folderId: String,
        activity: Activity
    ): Result<List<NoteFile>> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = buildListUrl(folderId) +
                "?\$select=id,name,lastModifiedDateTime"

            Log.d(TAG, "Listing files in folder: $folderId")
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "List files failed: ${response.code} $body")
                return@withContext Result.failure(IOException("List failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val items = json.getJSONArray("value")
            val notes = mutableListOf<NoteFile>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("name", "")
                // Filter to only .txt and .md files
                if (name.endsWith(".txt", ignoreCase = true) ||
                    name.endsWith(".md", ignoreCase = true)
                ) {
                    notes.add(
                        NoteFile(
                            id = item.getString("id"),
                            name = name,
                            lastModified = item.optString("lastModifiedDateTime", "")
                        )
                    )
                }
            }

            notes.sortBy { it.name.lowercase() }
            Log.d(TAG, "Found ${notes.size} note files")
            Result.success(notes)
        } catch (e: Exception) {
            Log.e(TAG, "listNoteFiles exception", e)
            Result.failure(e)
        }
    }

    // ─── File content ────────────────────────────────────────────────────────────

    /**
     * Download and return the text content of a file.
     *
     * @param fileId    OneDrive item ID.
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun downloadFileContent(
        fileId: String,
        activity: Activity
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = "$GRAPH_BASE/me/drive/items/$fileId/content"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            Log.d(TAG, "Downloading file: $fileId")
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Download failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("Download failed: ${response.code}"))
            }

            val content = response.body?.string() ?: ""
            Log.d(TAG, "Downloaded ${content.length} chars")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "downloadFileContent exception", e)
            Result.failure(e)
        }
    }

    /**
     * Download and return the raw bytes of a file (images, PDFs, or any binary content).
     *
     * @param fileId    OneDrive item ID.
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun downloadFileBytes(
        fileId: String,
        activity: Activity
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = "$GRAPH_BASE/me/drive/items/$fileId/content"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            Log.d(TAG, "Downloading file bytes: $fileId")
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Download bytes failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("Download failed: ${response.code}"))
            }

            val bytes = response.body?.bytes() ?: ByteArray(0)
            Log.d(TAG, "Downloaded ${bytes.size} bytes")
            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "downloadFileBytes exception", e)
            Result.failure(e)
        }
    }

    /**
     * Download a server-generated thumbnail's bytes (small/medium/large) instead of the full
     * original file — dramatically smaller/faster for previews (inline markdown images, the
     * insert-photo picker) where full resolution isn't needed. Uses the same authenticated
     * `/content` sub-resource pattern as [downloadFileBytes], not a pre-signed URL.
     *
     * @param fileId    OneDrive item ID.
     * @param size      One of "small", "medium", "large".
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun downloadThumbnailBytes(
        fileId: String,
        size: String,
        activity: Activity
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = "$GRAPH_BASE/me/drive/items/$fileId/thumbnails/0/$size/content"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            Log.d(TAG, "Downloading thumbnail ($size): $fileId")
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "Download thumbnail failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("Thumbnail download failed: ${response.code}"))
            }

            val bytes = response.body?.bytes() ?: ByteArray(0)
            Log.d(TAG, "Downloaded thumbnail: ${bytes.size} bytes")
            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "downloadThumbnailBytes exception", e)
            Result.failure(e)
        }
    }

    /**
     * Resolve an OneDrive item by its root-relative path (e.g. "Notes/trip/photo.png"),
     * used to resolve relative markdown links/images.
     *
     * @param absolutePath  OneDrive-root-relative path, no leading slash.
     * @param activity      Required for interactive auth fallback.
     */
    suspend fun resolveItemByPath(
        absolutePath: String,
        activity: Activity
    ): Result<DriveItemMeta> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            // Trailing bare ":" (no slash) is required by Graph's path-addressing syntax when
            // attaching query params to the referenced item itself (as opposed to ":/content"
            // or ":/children", which address a sub-resource and need the slash).
            val url = "$GRAPH_BASE/me/drive/root:/${encodePathSegments(absolutePath)}:" +
                "?\$select=id,name,file,folder,@microsoft.graph.downloadUrl"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "resolveItemByPath failed: ${response.code} $body")
                return@withContext Result.failure(IOException("Resolve failed: ${response.code}"))
            }

            val json = JSONObject(body)
            Result.success(
                DriveItemMeta(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    mimeType = json.optJSONObject("file")?.optString("mimeType"),
                    downloadUrl = json.optString("@microsoft.graph.downloadUrl", null),
                    isFolder = json.has("folder")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "resolveItemByPath exception", e)
            Result.failure(e)
        }
    }

    // ─── File saving ─────────────────────────────────────────────────────────────

    /**
     * Overwrite an existing file with new text content.
     *
     * @param fileId    OneDrive item ID.
     * @param content   New text content.
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun saveFileContent(
        fileId: String,
        content: String,
        activity: Activity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = "$GRAPH_BASE/me/drive/items/$fileId/content"
            val body = content.toRequestBody(TEXT_PLAIN)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .put(body)
                .build()

            Log.d(TAG, "Saving file: $fileId (${content.length} chars)")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Save failed: ${response.code} $responseBody")
                return@withContext Result.failure(IOException("Save failed: ${response.code}"))
            }

            Log.d(TAG, "File saved successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveFileContent exception", e)
            Result.failure(e)
        }
    }

    // ─── File creation ───────────────────────────────────────────────────────────

    /**
     * Create a new file in the configured folder.
     *
     * @param folderPath  OneDrive folder path (e.g. "notes") or empty string for root.
     * @param fileName    New file name including extension (.txt or .md).
     * @param activity    Required for interactive auth fallback.
     * @return The newly created [NoteFile].
     */
    suspend fun createFile(
        folderPath: String,
        fileName: String,
        activity: Activity
    ): Result<NoteFile> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            // Build upload URL: /me/drive/root:/{folder}/{fileName}:/content
            val pathSegment = if (folderPath.isBlank()) fileName else "$folderPath/$fileName"
            val url = "$GRAPH_BASE/me/drive/root:/$pathSegment:/content"

            val body = "".toRequestBody(TEXT_PLAIN)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .put(body)
                .build()

            Log.d(TAG, "Creating file: $pathSegment")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "Create failed: ${response.code} $responseBody")
                return@withContext Result.failure(IOException("Create failed: ${response.code}"))
            }

            val json = JSONObject(responseBody)
            val newFile = NoteFile(
                id = json.getString("id"),
                name = json.getString("name"),
                lastModified = json.optString("lastModifiedDateTime", ""),
                parentFolderId = json.optJSONObject("parentReference")?.optString("id"),
                parentPath = folderPath
            )
            Log.d(TAG, "File created: ${newFile.name} (${newFile.id})")
            Result.success(newFile)
        } catch (e: Exception) {
            Log.e(TAG, "createFile exception", e)
            Result.failure(e)
        }
    }

    /**
     * Upload binary content (e.g. a photo) as a new file in [folderPath]. Uses Graph's
     * rename conflict behavior, so an existing file with the same name is never overwritten —
     * the returned [NoteFile.name] reflects whatever name Graph actually assigned.
     *
     * @param folderPath  OneDrive folder path (e.g. "Notes/trip") or empty string for root.
     * @param fileName    Suggested file name including extension.
     * @param bytes       Raw file content.
     * @param mimeType    Content type, e.g. "image/jpeg".
     * @param activity    Required for interactive auth fallback.
     */
    suspend fun uploadFileBytes(
        folderPath: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        activity: Activity
    ): Result<NoteFile> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val pathSegment = if (folderPath.isBlank()) fileName else "$folderPath/$fileName"
            val url = "$GRAPH_BASE/me/drive/root:/${encodePathSegments(pathSegment)}:/content" +
                "?@microsoft.graph.conflictBehavior=rename"

            val body = bytes.toRequestBody(mimeType.toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .put(body)
                .build()

            Log.d(TAG, "Uploading file: $pathSegment (${bytes.size} bytes)")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "Upload failed: ${response.code} $responseBody")
                return@withContext Result.failure(IOException("Upload failed: ${response.code}"))
            }

            val json = JSONObject(responseBody)
            val uploaded = NoteFile(
                id = json.getString("id"),
                name = json.getString("name"),
                lastModified = json.optString("lastModifiedDateTime", ""),
                mimeType = json.optJSONObject("file")?.optString("mimeType"),
                downloadUrl = json.optString("@microsoft.graph.downloadUrl", null),
                parentPath = folderPath
            )
            Log.d(TAG, "File uploaded: ${uploaded.name} (${uploaded.id})")
            Result.success(uploaded)
        } catch (e: Exception) {
            Log.e(TAG, "uploadFileBytes exception", e)
            Result.failure(e)
        }
    }

    // ─── Mixed folder+file listing (for file browser) ──────────────────────────────

    /**
     * List both folders and files (notes, images, PDFs, etc.) inside a given OneDrive folder.
     *
     * Returns a sorted [BrowserEntry] list: folders first (alphabetically),
     * then files (newest first).
     *
     * @param folderId    OneDrive item ID, or "root" for the drive root.
     * @param folderPath  OneDrive-root-relative path of this folder (e.g. "Notes/trip", or "" for root) —
     *                    stamped onto each returned [NoteFile] as `parentPath` for relative-link resolution.
     * @param activity    Required for interactive auth fallback.
     */
    suspend fun listFolderContents(
        folderId: String,
        folderPath: String,
        activity: Activity
    ): Result<List<BrowserEntry>> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }
            val url = buildListUrl(folderId) +
                "?\$select=id,name,lastModifiedDateTime,folder,file,size,@microsoft.graph.downloadUrl&\$top=200"

            Log.d(TAG, "Listing folder contents: $folderId")
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "listFolderContents failed: ${response.code} $body")
                return@withContext Result.failure(IOException("List failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val items = json.getJSONArray("value")
            val entries = mutableListOf<BrowserEntry>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("name", "")
                when {
                    item.has("folder") ->
                        entries.add(
                            BrowserEntry.Folder(
                                FolderItem(
                                    id = item.getString("id"),
                                    name = name
                                )
                            )
                        )
                    item.has("file") ->
                        entries.add(
                            BrowserEntry.File(
                                NoteFile(
                                    id = item.getString("id"),
                                    name = name,
                                    lastModified = item.optString("lastModifiedDateTime", ""),
                                    mimeType = item.optJSONObject("file")?.optString("mimeType"),
                                    downloadUrl = item.optString("@microsoft.graph.downloadUrl", null),
                                    parentFolderId = folderId,
                                    parentPath = folderPath
                                )
                            )
                        )
                }
            }

            // Folders first (alphabetically), then files by lastModified descending (newest first)
            entries.sortWith(
                Comparator { a, b ->
                    val aIsFile = a is BrowserEntry.File
                    val bIsFile = b is BrowserEntry.File
                    // Folders before files
                    if (aIsFile != bIsFile) return@Comparator aIsFile.compareTo(bIsFile)
                    when {
                        // Both folders: alphabetical
                        a is BrowserEntry.Folder && b is BrowserEntry.Folder ->
                            a.item.name.lowercase().compareTo(b.item.name.lowercase())
                        // Both files: newest first; blank lastModified goes last
                        a is BrowserEntry.File && b is BrowserEntry.File -> {
                            val tsA = a.note.lastModified
                            val tsB = b.note.lastModified
                            when {
                                tsA.isBlank() && tsB.isBlank() -> a.note.name.lowercase().compareTo(b.note.name.lowercase())
                                tsA.isBlank() -> 1
                                tsB.isBlank() -> -1
                                else -> tsB.compareTo(tsA) // ISO-8601 desc sort
                            }
                        }
                        else -> 0
                    }
                }
            )
            Log.d(TAG, "Found ${entries.size} entries (folders+files)")
            Result.success(entries)
        } catch (e: Exception) {
            Log.e(TAG, "listFolderContents exception", e)
            Result.failure(e)
        }
    }

    // ─── Folder listing (for Settings folder picker) ─────────────────────────────

    /**
     * List OneDrive folders for the folder picker in Settings.
     *
     * @param parentId  Parent folder ID, or "root" for the drive root.
     * @param activity  Required for interactive auth fallback.
     * @return List of (id, name) pairs for each folder.
     */
    suspend fun listFolders(
        parentId: String = "root",
        activity: Activity
    ): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }

            val url = buildListUrl(parentId) +
                "?\$filter=folder+ne+null&\$select=id,name"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("List folders failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val items = json.getJSONArray("value")
            val folders = mutableListOf<Pair<String, String>>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                // Return (name, id) pairs so callers can destructure as (name, id)
                folders.add(item.getString("name") to item.getString("id"))
            }

            folders.sortBy { it.first.lowercase() }  // first=name after pair swap fix
            Result.success(folders)
        } catch (e: Exception) {
            Log.e(TAG, "listFolders exception", e)
            Result.failure(e)
        }
    }

    // ─── File deletion ─────────────────────────────────────────────────────────────

    /**
     * Permanently delete a file from OneDrive.
     *
     * @param fileId    OneDrive item ID to delete.
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun deleteFile(fileId: String, activity: Activity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }
            val url = "$GRAPH_BASE/me/drive/items/$fileId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                Log.d(TAG, "File deleted: $fileId")
                Result.success(Unit)
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Delete failed: ${response.code} $errBody")
                Result.failure(IOException("Delete failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile exception", e)
            Result.failure(e)
        }
    }

    // ─── Move / rename ───────────────────────────────────────────────────────────

    /**
     * Move a file into a different folder via Graph's `parentReference` PATCH.
     *
     * @param fileId          OneDrive item ID of the file being moved.
     * @param destFolderId    OneDrive item ID of the destination folder, or "root".
     * @param activity        Required for interactive auth fallback.
     */
    suspend fun moveItem(
        fileId: String,
        destFolderId: String,
        activity: Activity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }
            val url = "$GRAPH_BASE/me/drive/items/$fileId"
            val json = JSONObject().apply {
                put("parentReference", JSONObject().apply { put("id", destFolderId) })
            }
            val body = json.toString().toRequestBody(APPLICATION_JSON)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(body)
                .build()

            Log.d(TAG, "Moving item $fileId to folder $destFolderId")
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Move failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("Move failed: ${response.code}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "moveItem exception", e)
            Result.failure(e)
        }
    }

    /**
     * Rename a file in place via Graph's `name` PATCH.
     *
     * @param fileId    OneDrive item ID of the file being renamed.
     * @param newName   New file name, including extension.
     * @param activity  Required for interactive auth fallback.
     */
    suspend fun renameItem(
        fileId: String,
        newName: String,
        activity: Activity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = msalManager.acquireToken(activity).getOrElse { e ->
                return@withContext Result.failure(e)
            }
            val url = "$GRAPH_BASE/me/drive/items/$fileId"
            val json = JSONObject().apply { put("name", newName) }
            val body = json.toString().toRequestBody(APPLICATION_JSON)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(body)
                .build()

            Log.d(TAG, "Renaming item $fileId to $newName")
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Rename failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("Rename failed: ${response.code}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "renameItem exception", e)
            Result.failure(e)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Percent-encodes each segment of an OneDrive-root-relative path, preserving '/'. */
    private fun encodePathSegments(path: String): String =
        path.trim('/').split("/").joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }

    private fun buildListUrl(folderId: String): String =
        if (folderId == "root") {
            "$GRAPH_BASE/me/drive/root/children"
        } else {
            "$GRAPH_BASE/me/drive/items/$folderId/children"
        }
}
