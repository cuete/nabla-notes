package com.nabla.notes.ui.editor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.text.style.BackgroundColorSpan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nabla.notes.ui.common.ZoomableImage
import com.nabla.notes.markdown.NablaLink
import com.nabla.notes.markdown.countTaskItems
import com.nabla.notes.markdown.normalizeBareTaskLines
import com.nabla.notes.markdown.relativeLinkResolverPlugin
import com.nabla.notes.markdown.taskListTogglePlugin
import com.nabla.notes.model.FileKind
import com.nabla.notes.model.MarkdownAction
import com.nabla.notes.ui.common.DictationKeyboardControl
import com.nabla.notes.viewmodel.DictationSessionState
import com.nabla.notes.viewmodel.DictationViewModel
import com.nabla.voice.DictationMode
import com.nabla.notes.model.NoteFile
import com.nabla.notes.viewmodel.EditorUiState
import com.nabla.notes.viewmodel.EditorViewModel
import com.nabla.notes.viewmodel.OrganizeState
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.util.concurrent.atomic.AtomicInteger

// ── Markdown segment model ────────────────────────────────────────────────────

private sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class Mermaid(val diagram: String) : MarkdownSegment()
}

private fun splitMarkdownSegments(content: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val regex = Regex("""```mermaid[^\n]*\r?\n(.*?)\r?\n[ \t]*```""", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    for (match in regex.findAll(content)) {
        if (match.range.first > lastEnd) {
            segments.add(MarkdownSegment.Text(content.substring(lastEnd, match.range.first)))
        }
        val diagram = match.groupValues[1].trim().replace("\r", "")
        segments.add(MarkdownSegment.Mermaid(diagram))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < content.length) {
        segments.add(MarkdownSegment.Text(content.substring(lastEnd)))
    }
    android.util.Log.d("NablaNotes", "Segments: ${segments.size}, types: ${segments.map { it.javaClass.simpleName }}")
    return segments
}

// ── Editor Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    noteFile: NoteFile,
    showBack: Boolean,
    onBackClick: () -> Unit,
    onOpenFile: (id: String, kind: FileKind, name: String) -> Unit = { _, _, _ -> },
    viewModel: EditorViewModel = hiltViewModel(),
    dictationViewModel: DictationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val textFieldValue by viewModel.textFieldValue.collectAsState()
    val isMarkdownPreview by viewModel.isMarkdownPreview.collectAsState()
    val isUploadingPhoto by viewModel.isUploadingPhoto.collectAsState()
    val organizeState by viewModel.organizeState.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val activity = LocalContext.current as Activity
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showInsertImageSheet by remember { mutableStateOf(false) }

    // Force a save when the app is minimized and when this screen leaves composition (back,
    // file switch) instead of waiting out the autosave debounce.
    // purpose: viewModel/activity are stable for this composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushSave(activity)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushSave(activity)
        }
    }

    // Load file on first composition or when file changes
    LaunchedEffect(noteFile.id) {
        viewModel.loadFile(noteFile, activity)
    }

    // ── Dictate-at-cursor (P4) ──────────────────────────────────────────────────
    // Sessions started here are "inline": the service routes their utterances to
    // inlineUtterances instead of the transcript, so nothing dictated at the cursor leaks into
    // the Dictation screen's buffer, and a session some OTHER screen started never types into
    // whatever note happens to be open.
    val dictationState by dictationViewModel.state.collectAsState()
    val editorFocusRequester = remember { FocusRequester() }
    DictationKeyboardControl(
        recording = dictationState is DictationSessionState.Recording,
        onRestore = { runCatching { editorFocusRequester.requestFocus() } }
    )
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            dictationViewModel.setMode(DictationMode.NOTES)
            viewModel.beginInlineDictation()
            dictationViewModel.startSession(inline = true)
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar("Microphone permission is required to dictate.") }
        }
    }

    LaunchedEffect(Unit) { dictationViewModel.setActivity(activity) }

    LaunchedEffect(Unit) {
        dictationViewModel.inlineUtterances.collect { viewModel.insertDictatedText(it, activity) }
    }

    fun toggleDictation() {
        if (dictationState is DictationSessionState.Recording) {
            dictationViewModel.stopSession()
            return
        }
        val hasPermission = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            dictationViewModel.setMode(DictationMode.NOTES)
            viewModel.beginInlineDictation()
            dictationViewModel.startSession(inline = true)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    (organizeState as? OrganizeState.Failed)?.let { failed ->
        LaunchedEffect(failed) {
            snackbarHostState.showSnackbar("Organize failed: ${failed.message}")
            viewModel.dismissOrganize()
        }
    }
    (organizeState as? OrganizeState.Ready)?.let { ready ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissOrganize() },
            title = { Text("Organized note") },
            text = {
                Text(
                    ready.organized,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.applyOrganized(activity) }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissOrganize() }) { Text("Discard") } }
        )
    }

    // Show "Saved" snackbar on explicit (non-silent) save
    LaunchedEffect(uiState) {
        if (uiState is EditorUiState.Saved) {
            snackbarHostState.showSnackbar("Saved")
        }
    }

    val hasUnsaved = viewModel.hasUnsavedChanges
    val titleText = buildString {
        append(noteFile.name)
        if (hasUnsaved) append(" \u25CF")
    }

    // Long-tap handler passed down to mermaid diagram composables
    // Receives already-decoded PNG bytes from the bitmap in composable state
    val onSaveToGallery: (ByteArray) -> Unit = { pngBytes ->
        coroutineScope.launch {
            viewModel.saveSingleDiagram(pngBytes) { success ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        if (success) "Diagrama guardado en galería" else "Error al guardar diagrama"
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    // Dictate at cursor — only in edit mode; dictating into a read-only preview
                    // has nowhere to insert.
                    if (!isMarkdownPreview) {
                        val recording = dictationState is DictationSessionState.Recording
                        IconButton(onClick = { toggleDictation() }) {
                            Icon(
                                imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = if (recording) "Stop dictating" else "Dictate",
                                tint = if (recording) MaterialTheme.colorScheme.error else LocalContentColor.current
                            )
                        }
                    }
                    // Font size zoom — only meaningful while editing raw text.
                    if (!isMarkdownPreview) {
                        IconButton(onClick = { viewModel.decreaseFontSize() }) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Decrease text size")
                        }
                        IconButton(onClick = { viewModel.increaseFontSize() }) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = "Increase text size")
                        }
                    }
                    // Organize — AI cleanup of the whole note; shows a proposal to Apply/Discard.
                    IconButton(
                        onClick = { viewModel.organize() },
                        enabled = organizeState !is OrganizeState.Working && textFieldValue.text.isNotBlank()
                    ) {
                        if (organizeState is OrganizeState.Working) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = "Organize")
                        }
                    }
                    // Toggle preview / edit mode (only for .md files or when in preview)
                    if (noteFile.isMarkdown || isMarkdownPreview) {
                        IconButton(onClick = { viewModel.toggleMarkdownPreview() }) {
                            Icon(
                                imageVector = if (isMarkdownPreview) Icons.Filled.Edit else Icons.Filled.Visibility,
                                contentDescription = if (isMarkdownPreview) "Edit" else "Preview"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding()) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is EditorUiState.Idle,
                is EditorUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is EditorUiState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }

                is EditorUiState.Ready,
                is EditorUiState.Saving,
                is EditorUiState.Saved -> {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        if (isMarkdownPreview) {
                            MarkdownPreview(
                                content = textFieldValue.text,
                                onSaveToGallery = onSaveToGallery,
                                onToggleTask = { ordinal -> viewModel.toggleTaskItem(ordinal, activity) },
                                resolveContent = { text -> viewModel.resolveMediaLinks(text, activity) },
                                onOpenResolvedLink = { link -> onOpenFile(link.id, link.kind, link.name) },
                                modifier = Modifier
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        } else {
                            NoteEditor(
                                textFieldValue = textFieldValue,
                                onValueChange = { viewModel.updateTextFieldValue(it, activity) },
                                focusRequester = editorFocusRequester,
                                fontSize = fontSize,
                                modifier = Modifier
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                            MarkdownToolbar(
                                onAction = { action ->
                                    when (action) {
                                        MarkdownAction.UNDO -> viewModel.undo()
                                        MarkdownAction.REDO -> viewModel.redo()
                                        MarkdownAction.IMAGE -> {
                                            viewModel.loadFolderImages(activity)
                                            showInsertImageSheet = true
                                        }
                                        else -> viewModel.insertMarkdown(action)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (isUploadingPhoto) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Uploading photo…")
                        }
                    }
                }
            }
        }
    }

    if (showInsertImageSheet) {
        val folderImages by viewModel.folderImages.collectAsState()
        InsertImageSheet(
            folderImages = folderImages,
            onDismiss = { showInsertImageSheet = false },
            onExistingSelected = { name ->
                viewModel.insertImageLink(name, activity)
                showInsertImageSheet = false
            },
            onPhotoPicked = { bytes, mimeType ->
                coroutineScope.launch {
                    viewModel.uploadAndInsertPhoto(bytes, mimeType, activity) { success ->
                        coroutineScope.launch {
                            if (!success) snackbarHostState.showSnackbar("Failed to upload photo")
                        }
                    }
                }
                showInsertImageSheet = false
            },
            loadThumbnail = { fileId -> viewModel.downloadImageBytes(fileId, activity) }
        )
    }
}

// ── Mermaid diagram composable ────────────────────────────────────────────────

// Shared OkHttpClient for mermaid POST requests — one instance, reused across composables
private val mermaidHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/**
 * Fetches a Mermaid diagram image via GET from mermaid.ink.
 * Encodes the diagram as URL-safe Base64 and appends to the img endpoint.
 * Returns PNG bytes on success, null on failure.
 */
private suspend fun fetchMermaidPng(diagram: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val encoded = android.util.Base64.encodeToString(
            diagram.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val url = "https://mermaid.ink/img/$encoded"
        android.util.Log.d("NablaNotes", "Fetching mermaid: URL length=${url.length}")
        val request = Request.Builder().url(url).get().build()
        mermaidHttpClient.newCall(request).execute().use { response ->
            android.util.Log.d("NablaNotes", "Mermaid response: HTTP ${response.code}")
            if (!response.isSuccessful) return@withContext null
            response.body?.bytes()
        }
    } catch (e: Exception) {
        android.util.Log.e("NablaNotes", "Mermaid fetch failed", e)
        null
    }
}

@Composable
private fun MermaidDiagramView(
    diagram: String,
    onSaveToGallery: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(diagram) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(diagram) { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }
    // Cached PNG bytes so long-press save skips network re-fetch
    var cachedPngBytes by remember(diagram) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(diagram) {
        bitmap = null
        loadFailed = false
        cachedPngBytes = null
        val pngBytes = fetchMermaidPng(diagram)
        if (pngBytes != null) {
            val decoded = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            if (decoded != null) {
                cachedPngBytes = pngBytes
                bitmap = decoded
            } else {
                loadFailed = true
            }
        } else {
            loadFailed = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .pointerInput(diagram) {
                detectTapGestures(
                    onTap = { _ -> showFullscreen = true },
                    onLongPress = { _ -> cachedPngBytes?.let { onSaveToGallery(it) } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Mermaid diagram",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
            loadFailed -> Text(
                text = "⚠ Failed to load diagram",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
            else -> CircularProgressIndicator()
        }
    }

    if (showFullscreen) {
        bitmap?.let { bmp ->
            ZoomableImageDialog(
                bitmap = bmp,
                pngBytes = cachedPngBytes,
                onSaveToGallery = onSaveToGallery,
                onDismiss = { showFullscreen = false }
            )
        }
    }
}

// ── Fullscreen zoomable dialog ────────────────────────────────────────────────

@Composable
private fun ZoomableImageDialog(
    bitmap: Bitmap,
    pngBytes: ByteArray?,
    onSaveToGallery: (ByteArray) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Outer box — tap the black background to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { _ -> onDismiss() })
                },
            contentAlignment = Alignment.Center
        ) {
            ZoomableImage(
                bitmap = bitmap,
                contentDescription = "Mermaid diagram fullscreen",
                modifier = Modifier.fillMaxWidth(),
                onLongPress = { pngBytes?.let { onSaveToGallery(it) } }
            )

            // Close (X) button — top-right corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    tint = Color.White
                )
            }
        }
    }
}

// ── Plain Text Editor ─────────────────────────────────────────────────────────

@Composable
private fun NoteEditor(
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    fontSize: Float,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    BasicTextField(
        value = textFieldValue,
        onValueChange = onValueChange,
        // Fix: fillMaxSize() ensures the tappable area covers the full viewport so tapping
        // past the last character on any line still hits the field and places the cursor.
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            if (textFieldValue.text.isEmpty()) {
                Text(
                    text = "Start writing\u2026",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            innerTextField()
        }
    )
}

// ── Markdown Toolbar ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownToolbar(
    onAction: (MarkdownAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(MarkdownAction.entries.toList()) { action ->
                TextButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 27.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 15.sp),
                        fontFamily = if (action == MarkdownAction.CODE_BLOCK) FontFamily.Monospace else FontFamily.Default,
                        lineHeight = 27.sp
                    )
                }
            }
        }
    }
}

// ── Insert Photo Sheet ────────────────────────────────────────────────────────

/**
 * Bottom sheet offered from the Image toolbar button: link to a photo already in the note's
 * folder, or upload a new one from the gallery or camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsertImageSheet(
    folderImages: List<NoteFile>,
    onDismiss: () -> Unit,
    onExistingSelected: (fileName: String) -> Unit,
    onPhotoPicked: (bytes: ByteArray, mimeType: String) -> Unit,
    loadThumbnail: suspend (fileId: String) -> ByteArray?
) {
    val context = LocalContext.current
    var showFolderList by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) onPhotoPicked(bytes, mimeType) else onDismiss()
        } else {
            onDismiss()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            onPhotoPicked(file.readBytes(), "image/jpeg")
        } else {
            onDismiss()
        }
        file?.delete()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (!showFolderList) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                InsertImageSheetRow("Choose from this folder") { showFolderList = true }
                InsertImageSheetRow("Choose from gallery") {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                InsertImageSheetRow("Take photo") {
                    val dir = java.io.File(context.cacheDir, "camera_captures").apply { mkdirs() }
                    val file = java.io.File(dir, "capture_${System.currentTimeMillis()}.jpg")
                    pendingCameraFile = file
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    cameraLauncher.launch(uri)
                }
            }
        } else if (folderImages.isEmpty()) {
            Text(
                text = "No images in this folder",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(folderImages, key = { it.id }) { image ->
                    InsertImageSheetThumbnail(image, loadThumbnail) { onExistingSelected(image.name) }
                }
            }
        }
    }
}

@Composable
private fun InsertImageSheetRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun InsertImageSheetThumbnail(
    image: NoteFile,
    loadThumbnail: suspend (fileId: String) -> ByteArray?,
    onClick: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, image.id) {
        val bytes = loadThumbnail(image.id)
        value = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }
}

// ── Markdown Preview ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownPreview(
    content: String,
    onSaveToGallery: (ByteArray) -> Unit,
    onToggleTask: (Int) -> Unit,
    resolveContent: suspend (String) -> String,
    onOpenResolvedLink: (NablaLink) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val taskCounter = remember { AtomicInteger(0) }
    // Held null until media links (photos) finish resolving, so text and images render
    // together in one pass instead of text appearing first and photos popping in afterward
    // (which also shifted scroll position once the newly-laid-out images grew the content).
    var resolvedContent by remember(content) { mutableStateOf<String?>(null) }
    LaunchedEffect(content) { resolvedContent = resolveContent(content) }
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(GlideImagesPlugin.create(context))
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(taskListTogglePlugin(taskCounter, onToggleTask))
            .usePlugin(relativeLinkResolverPlugin(onOpenResolvedLink))
            .usePlugin(HtmlPlugin.create { plugin ->
                plugin.addHandler(object : TagHandler() {
                    override fun supportedTags() = listOf("mark")
                    override fun handle(
                        visitor: MarkwonVisitor,
                        renderer: MarkwonHtmlRenderer,
                        tag: HtmlTag
                    ) {
                        // <mark> is treated as a block tag by Markwon's HTML parser
                        // (not in its INLINE_TAGS set). Visit child tags to apply nested
                        // formatting (e.g., <mark><b>bold</b></mark>).
                        if (tag.isBlock) {
                            visitChildren(visitor, renderer, tag.getAsBlock())
                        }
                        // Text content is already in the builder; start/end are valid.
                        val start = tag.start()
                        val end = tag.end()
                        if (start < end) {
                            SpannableBuilder.setSpans(
                                visitor.builder(),
                                BackgroundColorSpan(0xFFFFE000.toInt()),
                                start,
                                end
                            )
                        }
                    }
                })
            })
            .build()
    }

    val resolved = resolvedContent
    if (resolved == null) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        }
        return
    }

    val segments = remember(resolved) { splitMarkdownSegments(resolved) }
    // Ordinal of the first checkbox in each segment, so a shared Markwon instance/plugin can
    // report task ordinals consistent with countTaskItems/flipTaskCheckbox over the full content.
    val taskBaseOffsets = remember(segments) {
        val offsets = IntArray(segments.size)
        var running = 0
        segments.forEachIndexed { i, seg ->
            offsets[i] = running
            if (seg is MarkdownSegment.Text) running += countTaskItems(seg.content)
        }
        offsets
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is MarkdownSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        AndroidView(
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    setPadding(0, 0, 0, 0)
                                    textSize = 15f
                                }
                            },
                            update = { textView ->
                                // Set markdown first so Markwon's spans (including TaskListSpan) are
                                // applied before setTextIsSelectable re-wraps the buffer. Calling
                                // setTextIsSelectable before setText loses ReplacementSpan drawables.
                                taskCounter.set(taskBaseOffsets[index])
                                markwon.setMarkdown(textView, normalizeBareTaskLines(segment.content))
                                textView.setTextIsSelectable(true)
                                textView.movementMethod = LinkMovementMethod.getInstance()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is MarkdownSegment.Mermaid -> {
                    MermaidDiagramView(
                        diagram = segment.diagram,
                        onSaveToGallery = onSaveToGallery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
        // Extra space at the bottom so content clears the keyboard / nav bar
        Spacer(modifier = Modifier.height(300.dp))
    }
}
