package com.nabla.notes.ui.editor

import android.app.Activity
import android.graphics.Bitmap
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.text.style.BackgroundColorSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.nabla.notes.model.MarkdownAction
import com.nabla.notes.model.NoteFile
import com.nabla.notes.viewmodel.EditorUiState
import com.nabla.notes.viewmodel.EditorViewModel
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
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
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val textFieldValue by viewModel.textFieldValue.collectAsState()
    val isMarkdownPreview by viewModel.isMarkdownPreview.collectAsState()
    val activity = LocalContext.current as Activity
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Load file on first composition or when file changes
    LaunchedEffect(noteFile.id) {
        viewModel.loadFile(noteFile, activity)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                modifier = Modifier
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        } else {
                            NoteEditor(
                                textFieldValue = textFieldValue,
                                onValueChange = { viewModel.updateTextFieldValue(it, activity) },
                                modifier = Modifier
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                            MarkdownToolbar(
                                onAction = { action ->
                                    when (action) {
                                        MarkdownAction.UNDO -> viewModel.undo()
                                        MarkdownAction.REDO -> viewModel.redo()
                                        else -> viewModel.insertMarkdown(action)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
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
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += panChange
    }

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
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Mermaid diagram fullscreen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(transformableState)
                    // Consume taps; long press saves to gallery using cached PNG bytes
                    .pointerInput(pngBytes) {
                        detectTapGestures(
                            onTap = { /* absorb — prevents dismiss */ },
                            onLongPress = { _ -> pngBytes?.let { onSaveToGallery(it) } }
                        )
                    }
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
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            if (textFieldValue.text.isEmpty()) {
                Text(
                    text = "Start writing\u2026",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
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

// ── Markdown Preview ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownPreview(
    content: String,
    onSaveToGallery: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(GlideImagesPlugin.create(context))
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

    val segments = remember(content) { splitMarkdownSegments(content) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        segments.forEach { segment ->
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
                                markwon.setMarkdown(textView, segment.content)
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
