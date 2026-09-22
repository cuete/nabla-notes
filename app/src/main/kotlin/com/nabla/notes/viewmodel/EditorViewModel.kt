package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.markdown.flipTaskCheckbox
import com.nabla.notes.markdown.resolveRelativeMediaLinks
import com.nabla.notes.model.BrowserEntry
import com.nabla.notes.model.FileKind
import com.nabla.notes.model.MarkdownAction
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.summarizer.Summarizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** UI state for the editor screen. */
sealed class EditorUiState {
    object Idle : EditorUiState()
    object Loading : EditorUiState()
    object Ready : EditorUiState()
    object Saving : EditorUiState()
    object Saved : EditorUiState()
    data class Error(val message: String) : EditorUiState()
}

/** State of the "Organize" (AI cleanup) action. [Ready] holds a proposal awaiting Apply/Discard. */
sealed class OrganizeState {
    object Idle : OrganizeState()
    object Working : OrganizeState()
    data class Ready(val organized: String) : OrganizeState()
    data class Failed(val message: String) : OrganizeState()
}

/**
 * ViewModel for the note editor.
 *
 * Responsibilities:
 *  - Load file content from OneDrive
 *  - Track edits in memory (with cursor/selection via TextFieldValue)
 *  - Save file content back to OneDrive (with debounced autosave)
 *  - Toggle between edit mode and markdown preview
 *  - Insert markdown formatting via toolbar actions
 *  - Undo/redo history stack
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val oneDriveRepository: OneDriveRepository,
    private val summarizer: Summarizer
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** Current text content with cursor/selection tracking. */
    private val _textFieldValue = MutableStateFlow(TextFieldValue(""))
    val textFieldValue: StateFlow<TextFieldValue> = _textFieldValue.asStateFlow()

    /** Whether we are in markdown preview mode (vs raw text edit mode). */
    private val _isMarkdownPreview = MutableStateFlow(false)
    val isMarkdownPreview: StateFlow<Boolean> = _isMarkdownPreview.asStateFlow()

    /** The file currently being edited. */
    private val _currentFile = MutableStateFlow<NoteFile?>(null)
    val currentFile: StateFlow<NoteFile?> = _currentFile.asStateFlow()

    /** Images already present in the current file's OneDrive folder (for the insert-photo picker). */
    private val _folderImages = MutableStateFlow<List<NoteFile>>(emptyList())
    val folderImages: StateFlow<List<NoteFile>> = _folderImages.asStateFlow()

    /** True while a picked/captured photo is uploading — drives a progress indicator in the UI. */
    private val _isUploadingPhoto = MutableStateFlow(false)
    val isUploadingPhoto: StateFlow<Boolean> = _isUploadingPhoto.asStateFlow()

    /** Snapshot of content at last save, used to track unsaved changes. */
    private var savedContent: String = ""

    /** Whether the content has unsaved changes. */
    val hasUnsavedChanges: Boolean
        get() = _textFieldValue.value.text != savedContent

    /**
     * Rewrites relative-path markdown links/images in [content] to resolve against OneDrive,
     * using the current file's parent folder as the base path. See [resolveRelativeMediaLinks].
     */
    suspend fun resolveMediaLinks(content: String, activity: Activity): String {
        val parentPath = _currentFile.value?.parentPath.orEmpty()
        return resolveRelativeMediaLinks(content, parentPath, oneDriveRepository, activity)
    }

    // ─── Autosave ────────────────────────────────────────────────────────────────

    private var autosaveJob: Job? = null

    private fun scheduleAutosave(activity: Activity) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            if (hasUnsavedChanges) saveFile(activity, silent = true)
        }
    }

    /**
     * Save right now if there are unsaved edits, skipping the debounce — called when the app is
     * minimized or the editor is left, where waiting out [AUTOSAVE_DELAY_MS] would lose edits.
     * Runs in [flushScope], not viewModelScope: popping the editor clears this ViewModel, which
     * would cancel an in-flight save started from onDispose.
     */
    fun flushSave(activity: Activity) {
        autosaveJob?.cancel()
        if (_uiState.value is EditorUiState.Loading || _currentFile.value == null) return
        if (hasUnsavedChanges) saveFile(activity, silent = true, scope = flushScope)
    }

    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object {
        const val AUTOSAVE_DELAY_MS = 2000L
    }

    // ─── Undo / Redo ─────────────────────────────────────────────────────────────

    private val history = ArrayDeque<TextFieldValue>()   // undo stack
    private val future = ArrayDeque<TextFieldValue>()    // redo stack
    private val MAX_HISTORY = 50

    /** Push current state onto the undo stack before applying a new value. */
    private fun pushHistory(value: TextFieldValue) {
        if (history.lastOrNull()?.text == value.text) return  // skip if text unchanged
        history.addLast(value)
        if (history.size > MAX_HISTORY) history.removeFirst()
        future.clear()  // new change invalidates redo
    }

    fun undo() {
        if (history.isEmpty()) return
        future.addFirst(_textFieldValue.value)
        _textFieldValue.value = history.removeLast()
        autosaveJob?.cancel()  // don't autosave mid-undo
    }

    fun redo() {
        if (future.isEmpty()) return
        history.addLast(_textFieldValue.value)
        _textFieldValue.value = future.removeFirst()
        autosaveJob?.cancel()  // don't autosave mid-redo
    }

    // ─── Public actions ──────────────────────────────────────────────────────────

    /**
     * Load a note file from OneDrive.
     * If the same file is already loaded, does nothing (avoids redundant network calls).
     */
    fun loadFile(noteFile: NoteFile, activity: Activity) {
        if (_currentFile.value?.id == noteFile.id &&
            _uiState.value is EditorUiState.Ready
        ) {
            return // Already loaded
        }

        _currentFile.value = noteFile
        _uiState.value = EditorUiState.Loading
        // Default to markdown preview for .md files
        _isMarkdownPreview.value = noteFile.isMarkdown

        viewModelScope.launch {
            oneDriveRepository.downloadFileContent(
                fileId = noteFile.id,
                activity = activity
            ).fold(
                onSuccess = { text ->
                    setContent(text)
                    _uiState.value = EditorUiState.Ready
                },
                onFailure = { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "Failed to load file")
                }
            )
        }
    }

    /**
     * Set content from a string source (e.g. loaded from OneDrive).
     * Resets cursor to start and clears history.
     */
    fun setContent(text: String) {
        _textFieldValue.value = TextFieldValue(text)
        savedContent = text
        history.clear()
        future.clear()
    }

    /**
     * Called by BasicTextField on every keystroke to update text + cursor/selection.
     * Schedules a debounced autosave 2 seconds after the last keystroke.
     */
    fun updateTextFieldValue(value: TextFieldValue, activity: Activity) {
        pushHistory(_textFieldValue.value)
        _textFieldValue.value = value
        scheduleAutosave(activity)
    }

    /**
     * Toggle the [ordinal]-th checkbox (document order, see [countTaskItems]) in the current
     * content. Used from markdown preview mode, where checkboxes are tap-to-toggle.
     */
    fun toggleTaskItem(ordinal: Int, activity: Activity) {
        val current = _textFieldValue.value
        val newText = flipTaskCheckbox(current.text, ordinal)
        if (newText == current.text) return
        pushHistory(current)
        _textFieldValue.value = TextFieldValue(newText, current.selection)
        scheduleAutosave(activity)
    }

    /**
     * Load the list of images already sitting in the current file's OneDrive folder, for the
     * "choose from this folder" option in the insert-photo picker.
     */
    fun loadFolderImages(activity: Activity) {
        val file = _currentFile.value ?: return
        val folderId = file.parentFolderId ?: return
        viewModelScope.launch {
            oneDriveRepository.listFolderContents(folderId, file.parentPath.orEmpty(), activity)
                .onSuccess { entries ->
                    _folderImages.value = entries
                        .filterIsInstance<BrowserEntry.File>()
                        .map { it.note }
                        .filter { it.kind == FileKind.IMAGE }
                }
        }
    }

    /**
     * Fetch a small thumbnail's bytes for the insert-photo picker (falls back to full bytes if
     * no server-generated thumbnail exists yet, e.g. right after upload).
     */
    suspend fun downloadImageBytes(fileId: String, activity: Activity): ByteArray? {
        val thumbnail = oneDriveRepository.downloadThumbnailBytes(fileId, "small", activity)
        if (thumbnail.isSuccess) return thumbnail.getOrNull()
        return oneDriveRepository.downloadFileBytes(fileId, activity).getOrNull()
    }

    /**
     * Insert `![altText](fileName)` at the current cursor position. [fileName] alone (no path
     * prefix) resolves relative to the note's own folder — see [resolveRelativePath][com.nabla.notes.markdown.resolveRelativePath].
     */
    fun insertImageLink(fileName: String, activity: Activity) {
        val current = _textFieldValue.value
        val altText = fileName.substringBeforeLast('.')
        val insert = "![$altText]($fileName)"
        val newText = current.text.substring(0, current.selection.start) +
            insert +
            current.text.substring(current.selection.end)
        val newCursor = current.selection.start + insert.length
        pushHistory(current)
        _textFieldValue.value = TextFieldValue(newText, TextRange(newCursor))
        scheduleAutosave(activity)
    }

    private var stampNextDictation = false

    /** Call when an inline dictation session starts, so its first insert is timestamped. */
    fun beginInlineDictation() {
        stampNextDictation = true
    }

    /**
     * Insert dictated [text] at the current cursor position, trailing space so consecutive
     * utterances read as one continuous flow rather than running together — matches
     * [insertImageLink]'s insert-at-cursor pattern.
     */
    fun insertDictatedText(text: String, activity: Activity) {
        if (text.isBlank()) return
        val current = _textFieldValue.value
        // First insert of a dictation session gets a timestamp (own line if mid-line); later
        // utterances of the same session flow on without one, like Notes mode.
        val stamp = if (stampNextDictation) {
            stampNextDictation = false
            val atLineStart = current.selection.start == 0 ||
                current.text[current.selection.start - 1] == '\n'
            (if (atLineStart) "" else "\n") + "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}] "
        } else ""
        val insert = "$stamp$text "
        val newText = current.text.substring(0, current.selection.start) +
            insert +
            current.text.substring(current.selection.end)
        val newCursor = current.selection.start + insert.length
        pushHistory(current)
        _textFieldValue.value = TextFieldValue(newText, TextRange(newCursor))
        scheduleAutosave(activity)
    }

    /**
     * Upload a newly picked/captured photo into the current file's folder, then insert a
     * relative link to it (using whatever name Graph actually assigned, in case of a rename
     * conflict — see [OneDriveRepository.uploadFileBytes]).
     */
    fun uploadAndInsertPhoto(
        bytes: ByteArray,
        mimeType: String,
        activity: Activity,
        onDone: (success: Boolean) -> Unit
    ) {
        val file = _currentFile.value
        if (file == null) {
            onDone(false)
            return
        }
        _isUploadingPhoto.value = true
        viewModelScope.launch {
            val extension = when (mimeType) {
                "image/png" -> "png"
                else -> "jpg"
            }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = "Photo_$timestamp.$extension"

            oneDriveRepository.uploadFileBytes(
                folderPath = file.parentPath.orEmpty(),
                fileName = fileName,
                bytes = bytes,
                mimeType = mimeType,
                activity = activity
            ).fold(
                onSuccess = { uploaded ->
                    insertImageLink(uploaded.name, activity)
                    _isUploadingPhoto.value = false
                    onDone(true)
                },
                onFailure = {
                    _isUploadingPhoto.value = false
                    onDone(false)
                }
            )
        }
    }

    /**
     * Save the current content back to OneDrive.
     *
     * @param silent If true, no Saving/Saved UI state transitions (for autosave calls).
     */
    fun saveFile(activity: Activity, silent: Boolean = false, scope: CoroutineScope = viewModelScope) {
        val file = _currentFile.value ?: return
        val textToSave = _textFieldValue.value.text

        if (!silent) {
            _uiState.value = EditorUiState.Saving
        }

        scope.launch {
            oneDriveRepository.saveFileContent(
                fileId = file.id,
                content = textToSave,
                activity = activity
            ).fold(
                onSuccess = {
                    savedContent = textToSave
                    if (!silent) {
                        _uiState.value = EditorUiState.Saved
                        delay(1500)
                    }
                    _uiState.value = EditorUiState.Ready
                },
                onFailure = { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "Failed to save file")
                }
            )
        }
    }

    // ─── Organize (AI cleanup) ───────────────────────────────────────────────────

    private val _organizeState = MutableStateFlow<OrganizeState>(OrganizeState.Idle)
    val organizeState: StateFlow<OrganizeState> = _organizeState.asStateFlow()

    /** Ask the summarizer provider to reorganize the note; result waits for [applyOrganized]. */
    fun organize() {
        val text = _textFieldValue.value.text
        if (text.isBlank() || _organizeState.value is OrganizeState.Working) return
        _organizeState.value = OrganizeState.Working
        viewModelScope.launch {
            summarizer.organize(text).fold(
                onSuccess = { _organizeState.value = OrganizeState.Ready(stripCodeFence(it)) },
                onFailure = { _organizeState.value = OrganizeState.Failed(it.message ?: "Organize failed") }
            )
        }
    }

    /** Replace the note with the proposal (undoable via [undo]) and autosave. */
    fun applyOrganized(activity: Activity) {
        val ready = _organizeState.value as? OrganizeState.Ready ?: return
        val current = _textFieldValue.value
        pushHistory(current)
        _textFieldValue.value = TextFieldValue(ready.organized, TextRange(ready.organized.length))
        _organizeState.value = OrganizeState.Idle
        scheduleAutosave(activity)
    }

    fun dismissOrganize() {
        _organizeState.value = OrganizeState.Idle
    }

    /** Models sometimes wrap the whole reply in a ```markdown fence despite being told not to. */
    private fun stripCodeFence(reply: String): String {
        val trimmed = reply.trim()
        val fence = Regex("""^```[a-zA-Z]*\n([\s\S]*?)\n```$""").find(trimmed)
        return fence?.groupValues?.get(1) ?: trimmed
    }

    /**
     * Toggle between raw text editing and markdown preview.
     */
    fun toggleMarkdownPreview() {
        _isMarkdownPreview.value = !_isMarkdownPreview.value
    }

    /** True when content contains at least one mermaid fenced block. */
    fun hasMermaidDiagrams(): Boolean {
        val text = _textFieldValue.value.text
        return text.contains("```mermaid", ignoreCase = true)
    }

    /**
     * Download all mermaid diagrams from mermaid.ink and save them to the
     * device gallery via MediaStore. Runs network I/O on Dispatchers.IO.
     * [onResult] is invoked on the calling coroutine dispatcher (main) with
     * (saved, failed) counts.
     */
    fun saveMermaidDiagrams(context: Context, onResult: (saved: Int, failed: Int) -> Unit) {
        viewModelScope.launch {
            val content = _textFieldValue.value.text
            val regex = Regex("""```mermaid\s*\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            val diagrams = regex.findAll(content).map { it.groupValues[1].trim() }.toList()
            if (diagrams.isEmpty()) { onResult(0, 0); return@launch }

            var saved = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                // Use POST with JSON body — avoids GET URL length limits for large diagrams
                val client = OkHttpClient()
                val jsonMediaType = "application/json".toMediaType()
                for ((index, diagram) in diagrams.withIndex()) {
                    try {
                        val json = JSONObject().apply {
                            put("code", diagram)
                            put("mermaid", JSONObject().apply { put("theme", "default") })
                        }.toString()
                        val body = json.toRequestBody(jsonMediaType)
                        val request = Request.Builder()
                            .url("https://mermaid.ink/img")
                            .post(body)
                            .build()
                        val bytes = client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            response.body?.bytes()
                        } ?: throw Exception("Empty response")

                        val filename = "mermaid_${System.currentTimeMillis()}_$index.png"
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NablaNotes")
                            }
                        }
                        val uri = context.contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                        ) ?: throw Exception("Could not create MediaStore entry")
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        saved++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
            onResult(saved, failed)
        }
    }

    /**
     * Insert markdown formatting at the current cursor position or around the current selection.
     *
     * - Block actions (isBlock=true): insert prefix at the start of the current line.
     * - Inline with no selection: insert prefix+placeholder+suffix, select the placeholder.
     * - Inline with selection: wrap selected text with prefix/suffix.
     */
    fun insertMarkdown(action: MarkdownAction) {
        val current = _textFieldValue.value
        val text = current.text
        val selection = current.selection

        val newValue: TextFieldValue = if (action.isBlock) {
            // Find the start of the current line
            val lineStart = text.lastIndexOf('\n', selection.start - 1) + 1
            val newText = text.substring(0, lineStart) + action.prefix + text.substring(lineStart)
            val newCursor = lineStart + action.prefix.length + (selection.start - lineStart)
            TextFieldValue(newText, TextRange(newCursor))
        } else if (selection.start == selection.end) {
            // No selection: insert prefix+placeholder+suffix, select the placeholder
            val insert = action.prefix + action.placeholder + action.suffix
            val newText = text.substring(0, selection.start) + insert + text.substring(selection.end)
            val selectStart = selection.start + action.prefix.length
            val selectEnd = selectStart + action.placeholder.length
            TextFieldValue(newText, TextRange(selectStart, selectEnd))
        } else {
            // Has selection: wrap selected text with prefix/suffix
            val selected = text.substring(selection.start, selection.end)
            val wrapped = action.prefix + selected + action.suffix
            val newText = text.substring(0, selection.start) + wrapped + text.substring(selection.end)
            val newStart = selection.start + action.prefix.length
            val newEnd = newStart + selected.length
            TextFieldValue(newText, TextRange(newStart, newEnd))
        }

        _textFieldValue.value = newValue
    }

    /**
     * Save already-fetched PNG [bytes] from a Mermaid diagram to the device gallery.
     * Avoids a network re-fetch — the composable passes the cached bytes it received
     * from the original POST to mermaid.ink.
     * Uses the injected application context — no Activity reference needed.
     */
    fun saveSingleDiagram(bytes: ByteArray, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            var success = false
            withContext(Dispatchers.IO) {
                try {
                    val filename = "mermaid_${System.currentTimeMillis()}.png"
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(
                                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                                "Pictures/NablaNotes"
                            )
                        }
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw Exception("Could not create MediaStore entry")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    success = true
                } catch (_: Exception) {
                    // success stays false
                }
            }
            onResult(success)
        }
    }

    /**
     * Reset editor state (e.g. when selecting a new file in split-pane).
     */
    fun reset() {
        autosaveJob?.cancel()
        _currentFile.value = null
        _textFieldValue.value = TextFieldValue("")
        savedContent = ""
        _uiState.value = EditorUiState.Idle
        _isMarkdownPreview.value = false
        history.clear()
        future.clear()
    }
}
