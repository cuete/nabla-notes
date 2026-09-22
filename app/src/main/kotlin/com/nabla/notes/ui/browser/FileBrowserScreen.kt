package com.nabla.notes.ui.browser

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nabla.notes.model.BrowserEntry
import com.nabla.notes.model.FileKind
import com.nabla.notes.model.FolderItem
import com.nabla.notes.model.NoteFile
import com.nabla.notes.viewmodel.BrowserUiState
import com.nabla.notes.viewmodel.BrowserViewModel
import com.nabla.notes.viewmodel.MoveUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: BrowserViewModel,
    onFileSelected: (NoteFile) -> Unit,
    onSettingsClick: () -> Unit,
    onDictateClick: (folderPath: String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val folderStack by viewModel.folderStack.collectAsState()
    val activity = LocalContext.current as Activity

    // Intercept back button when inside a subfolder — navigate up instead of exiting
    BackHandler(enabled = folderStack.isNotEmpty()) {
        viewModel.navigateUp(activity)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<NoteFile?>(null) }
    var fileToRename by remember { mutableStateOf<NoteFile?>(null) }
    var fileToMove by remember { mutableStateOf<NoteFile?>(null) }

    // Trigger initial load
    LaunchedEffect(Unit) {
        if (uiState is BrowserUiState.Idle) {
            viewModel.loadFiles(activity)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "nabla notes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onDictateClick(viewModel.currentFolderPath()) }) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Dictate"
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState is BrowserUiState.Success) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "New file")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is BrowserUiState.Idle,
                is BrowserUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is BrowserUiState.SignInRequired -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Sign in to access your notes",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.signIn(activity) }) {
                            Text("Sign in with Microsoft")
                        }
                    }
                }

                is BrowserUiState.Success -> {
                    if (state.entries.isEmpty() && folderStack.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No notes yet. Tap + to create one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (state.entries.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Empty folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // "..": go-up entry shown when inside a subfolder
                            if (folderStack.isNotEmpty()) {
                                item(key = "go_up") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.navigateUp(activity) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Go up",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "..",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }

                            items(
                                items = state.entries,
                                key = { entry ->
                                    when (entry) {
                                        is BrowserEntry.Folder -> "folder_${entry.item.id}"
                                        is BrowserEntry.File   -> "file_${entry.note.id}"
                                    }
                                }
                            ) { entry ->
                                when (entry) {
                                    is BrowserEntry.Folder -> FolderEntryCard(
                                        folder = entry.item,
                                        onClick = { viewModel.navigateIntoFolder(entry.item, activity) }
                                    )
                                    is BrowserEntry.File -> NoteFileCard(
                                        noteFile = entry.note,
                                        onClick = { onFileSelected(entry.note) },
                                        onRenameClick = { fileToRename = entry.note },
                                        onMoveClick = {
                                            fileToMove = entry.note
                                            viewModel.startMove(entry.note, activity)
                                        },
                                        onDeleteClick = { fileToDelete = entry.note }
                                    )
                                }
                            }
                        }
                    }
                }

                is BrowserUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadFiles(activity) }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete note?") },
            text = {
                Text("\"${file.name}\" will be permanently deleted from OneDrive.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFile(file, activity)
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateDialog) {
        CreateFileDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, isMarkdown ->
                showCreateDialog = false
                viewModel.createFile(name, isMarkdown, activity) { newFile ->
                    onFileSelected(newFile)
                }
            }
        )
    }

    // Rename dialog
    fileToRename?.let { file ->
        RenameFileDialog(
            currentName = file.name,
            onDismiss = { fileToRename = null },
            onRename = { newName ->
                viewModel.renameFile(file, newName, activity)
                fileToRename = null
            }
        )
    }

    // Move-to-folder dialog
    fileToMove?.let { file ->
        val moveUiState by viewModel.moveUiState.collectAsState()
        val moveFolderStack by viewModel.moveFolderStack.collectAsState()
        // Move succeeded (or was cancelled) elsewhere and reset the ViewModel's state to
        // Hidden — dismiss the local dialog toggle to match.
        LaunchedEffect(moveUiState) {
            if (moveUiState is MoveUiState.Hidden) fileToMove = null
        }
        MoveFileDialog(
            file = file,
            uiState = moveUiState,
            folderStack = moveFolderStack,
            onNavigateInto = { id, name -> viewModel.moveNavigateInto(file, id, name, activity) },
            onNavigateUp = { viewModel.moveNavigateUp(file, activity) },
            onConfirmMove = { viewModel.confirmMove(file, activity) },
            onDismiss = {
                viewModel.cancelMove()
                fileToMove = null
            }
        )
    }
}

// ─── File Card ────────────────────────────────────────────────────────────────

// Fixed accent colors per entry kind — consistent across light/dark theme so folders and file
// types stay scannable by color, not just by icon shape.
private val FolderColor = Color(0xFFFFA726)
private fun fileKindColor(kind: FileKind): Color = when (kind) {
    FileKind.MARKDOWN -> Color(0xFF42A5F5)
    FileKind.TEXT -> Color(0xFF78909C)
    FileKind.IMAGE -> Color(0xFF66BB6A)
    FileKind.PDF -> Color(0xFFEF5350)
    FileKind.OTHER -> Color(0xFFAB47BC)
}

/** Icon on a soft tinted circular background — the color badge that makes kind scannable at a glance. */
@Composable
private fun EntryIconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, color: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─── Folder Card ────────────────────────────────────────────────────────────────────────────

@Composable
private fun FolderEntryCard(folder: FolderItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryIconBadge(Icons.Filled.Folder, "Folder", FolderColor)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── File Card ────────────────────────────────────────────────────────────────────────────

@Composable
private fun NoteFileCard(
    noteFile: NoteFile,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, description) = when (noteFile.kind) {
                FileKind.MARKDOWN -> Icons.Filled.Description to "Markdown file"
                FileKind.TEXT -> Icons.Filled.Article to "Text file"
                FileKind.IMAGE -> Icons.Filled.Image to "Image file"
                FileKind.PDF -> Icons.Filled.PictureAsPdf to "PDF file"
                FileKind.OTHER -> Icons.Filled.InsertDriveFile to "File"
            }
            EntryIconBadge(icon, description, fileKindColor(noteFile.kind))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = noteFile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (noteFile.lastModified.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatDate(noteFile.lastModified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Trailing overflow menu — rename / move / delete
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; onRenameClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Move") },
                        onClick = { showMenu = false; onMoveClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDeleteClick() }
                    )
                }
            }
        }
    }
}

// ─── Rename Dialog ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameFileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename file") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameError = false },
                label = { Text("File name") },
                isError = nameError,
                supportingText = if (nameError) {
                    { Text("Name cannot be empty") }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) nameError = true else onRename(name.trim())
                }
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ─── Move Dialog ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveFileDialog(
    file: NoteFile,
    uiState: MoveUiState,
    folderStack: List<Pair<String, String>>,
    onNavigateInto: (folderId: String, folderName: String) -> Unit,
    onNavigateUp: () -> Unit,
    onConfirmMove: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentPath = if (folderStack.isEmpty()) "OneDrive" else "OneDrive / " + folderStack.joinToString(" / ") { it.second }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"${file.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "📂 $currentPath",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (folderStack.isNotEmpty()) {
                    TextButton(onClick = onNavigateUp, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "↑ Go up",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                when (uiState) {
                    is MoveUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is MoveUiState.FolderPicker -> {
                        if (uiState.folders.isEmpty()) {
                            Text(
                                text = "No subfolders here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                uiState.folders.sortedBy { it.first.lowercase() }.forEach { (name, id) ->
                                    TextButton(
                                        onClick = { onNavigateInto(id, name) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "📁  $name  ›",
                                            modifier = Modifier.fillMaxWidth(),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MoveUiState.Error -> {
                        Text(
                            text = "Failed to load folders: ${uiState.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is MoveUiState.Hidden -> Unit
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirmMove) {
                Text("Move here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatDate(iso8601: String): String {
    return try {
        val instant = Instant.parse(iso8601)
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        iso8601
    }
}

// ─── Create File Dialog ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFileDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, isMarkdown: Boolean) -> Unit
) {
    var fileName by remember {
        val today = java.time.LocalDate.now().toString() // YYYY-MM-DD
        mutableStateOf("${today}_Note")
    }
    var isMarkdown by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        nameError = false
                    },
                    label = { Text("File name") },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("Name cannot be empty") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "File type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isMarkdown,
                        onClick = { isMarkdown = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(".txt")
                    }
                    SegmentedButton(
                        selected = isMarkdown,
                        onClick = { isMarkdown = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(".md")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileName.isBlank()) {
                        nameError = true
                    } else {
                        onCreate(fileName.trim(), isMarkdown)
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
