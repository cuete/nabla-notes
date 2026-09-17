package com.nabla.notes.ui.dictation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nabla.notes.model.NoteLine
import com.nabla.voice.DictationMode
import com.nabla.notes.viewmodel.DictationSessionState
import com.nabla.notes.viewmodel.DictationViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Three tabs long-term — Transcript / Notes / Summary — mirroring chato's original tab split
 * (device-testing feedback, 2026-09-16: "the 'type a note' section should be a new tab").
 * Only Transcript and Notes are real here; Summary needs the provider-agnostic summarizer seam
 * (a separate, not-yet-built phase — see DictationViewModel's class doc on why organizeNotes/
 * summarize weren't ported) — it shows as a disabled placeholder rather than a half-built stub.
 * Title + Save live on the Notes tab for now; per feedback they belong on the Summary tab
 * once it exists, with chato's original transcript/summary/both save-target choice. Moving
 * them is deferred with Summary itself rather than removing working save capability early.
 */
private enum class DictationTab(val label: String) {
    TRANSCRIPT("Transcript"),
    NOTES("Notes"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(
    onBackClick: () -> Unit,
    viewModel: DictationViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val mode by viewModel.mode.collectAsState()
    val state by viewModel.state.collectAsState()
    val noteLines by viewModel.noteLines.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var typedInput by remember { mutableStateOf("") }
    var saveTitle by remember { mutableStateOf(defaultNoteTitle()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.setActivity(activity) }

    LaunchedEffect(saveStatus) {
        saveStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveStatus()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSession(contextNotes = "")
        else scope.launch { snackbarHostState.showSnackbar("Microphone permission is required to dictate.") }
    }

    fun startOrRequestPermission() {
        val hasPermission = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) viewModel.startSession(contextNotes = "")
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictate") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (noteLines.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear notes")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Dictation settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Session controls — not tab-specific, stay visible no matter which tab is open.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DictationMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { viewModel.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DictationMode.entries.size)
                    ) {
                        Text(if (m == DictationMode.NOTES) "Notes" else "Conversation")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val recording = state is DictationSessionState.Recording
                Button(
                    onClick = { if (recording) viewModel.stopSession() else startOrRequestPermission() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                    Text(if (recording) "  Stop" else "  Start dictating")
                }
            }

            when (val s = state) {
                is DictationSessionState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is DictationSessionState.Stopping -> Text("Stopping…", style = MaterialTheme.typography.labelMedium)
                else -> {}
            }

            HorizontalDivider()

            TabRow(selectedTabIndex = selectedTab) {
                DictationTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) }
                    )
                }
                // Placeholder — see class doc. Disabled, not a working tab yet.
                Tab(selected = false, onClick = {}, enabled = false, text = { Text("Summary") })
            }

            when (DictationTab.entries[selectedTab]) {
                DictationTab.TRANSCRIPT -> {
                    // Read-only live view of everything said/typed so far, in order.
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(noteLines) { line -> NoteLineRow(line) }
                    }
                }

                DictationTab.NOTES -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = typedInput,
                            onValueChange = { typedInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Type a note") },
                            singleLine = true
                        )
                        Button(onClick = {
                            viewModel.addTypedText(typedInput)
                            typedInput = ""
                        }) { Text("Add") }
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(noteLines) { line -> NoteLineRow(line) }
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = saveTitle,
                        onValueChange = { saveTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true
                    )
                    Button(
                        onClick = { viewModel.saveNotesToOneDrive(saveTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = saveTitle.isNotBlank() && noteLines.isNotEmpty()
                    ) { Text("Save") }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear transcript and notes?") },
            text = { Text("This deletes the current transcript and pending notes. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearTranscript()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showSettingsDialog) {
        DictationSettingsDialog(
            initialKey = settings.azureSpeechKey,
            initialRegion = settings.azureSpeechRegion,
            onDismiss = { showSettingsDialog = false },
            onSave = { key, region ->
                viewModel.saveAzureSettings(key, region)
                showSettingsDialog = false
            }
        )
    }
}

/** "2026-09-16_Note" — today's date, editable before saving. */
private fun defaultNoteTitle(): String =
    "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}_Note"

@Composable
private fun NoteLineRow(line: NoteLine) {
    if (line.precededByGap) {
        Spacer(modifier = Modifier.height(12.dp))
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // Typed entries have no speaker to diarize — the 📝 marker is the only source cue,
        // same visual role the old separate pending-notes view served, now folded into one row.
        val label = if (line.typed) "[${line.timestamp}] 📝 " else "[${line.timestamp}] ${line.speakerId}: "
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = line.text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DictationSettingsDialog(
    initialKey: String,
    initialRegion: String,
    onDismiss: () -> Unit,
    onSave: (key: String, region: String) -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var region by remember { mutableStateOf(initialRegion) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dictation settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Azure Speech key") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Azure Speech region") },
                    singleLine = true
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, region) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
