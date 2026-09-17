package com.nabla.notes.ui.dictation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.nabla.voice.DictationMode
import com.nabla.voice.TranscriptEntry
import com.nabla.notes.viewmodel.DictationSessionState
import com.nabla.notes.viewmodel.DictationViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transcript / Notes / Summary (2026-09-16 device-testing feedback, mirroring chato's original
 * tab split). Notes is deliberately just a typed-text box, independent of the transcript — the
 * two get combined only as separate fields in a future summarizer payload, not merged into one
 * stream beforehand (an earlier round tried that unification; reverted). Summary is real UI —
 * not a stub screen — but its Summarize button stays disabled until the provider-agnostic
 * summarizer phase exists; see DictationViewModel's class doc.
 */
private enum class DictationTab(val label: String) {
    TRANSCRIPT("Transcript"),
    NOTES("Notes"),
    SUMMARY("Summary"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(
    folderPath: String,
    onBackClick: () -> Unit,
    viewModel: DictationViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val mode by viewModel.mode.collectAsState()
    val state by viewModel.state.collectAsState()
    val transcriptEntries by viewModel.transcriptEntries.collectAsState()
    val typedNotes by viewModel.typedNotes.collectAsState()
    val summaryText by viewModel.summaryText.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var saveTitle by remember { mutableStateOf(defaultNoteTitle()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.setActivity(activity) }
    LaunchedEffect(folderPath) { viewModel.setSaveFolder(folderPath) }

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
                    if (transcriptEntries.isNotEmpty() || typedNotes.isNotBlank()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear transcript and notes")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Dictation settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Same as EditorScreen: without this, Scaffold's own default inset handling interacts
        // with the child Column's imePadding() below and the keyboard ends up covering content
        // anyway (2026-09-16 device-testing feedback: Title box on Summary tab). Opting out here
        // makes imePadding() the sole source of truth for the IME inset.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Session controls — mode + Start/Stop share one row, not tab-specific, stay visible
            // no matter which tab is open. "Dictation" (was "Notes") to avoid confusion with the
            // Notes tab; "Start"/"Stop" instead of "Start dictating" now that they're side by side.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    DictationMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { viewModel.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DictationMode.entries.size)
                        ) {
                            Text(if (m == DictationMode.NOTES) "Dictation" else "Conversation")
                        }
                    }
                }
                val recording = state is DictationSessionState.Recording
                Button(onClick = { if (recording) viewModel.stopSession() else startOrRequestPermission() }) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                    Text(if (recording) "  Stop" else "  Start")
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
            }

            when (DictationTab.entries[selectedTab]) {
                DictationTab.TRANSCRIPT -> {
                    // Read-only live view — spoken entries only, in the same buffer regardless
                    // of which mode (Dictation/Conversation) recorded them, unchanged from
                    // before this round.
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(transcriptEntries) { entry -> TranscriptEntryRow(entry) }
                    }
                }

                DictationTab.NOTES -> {
                    // Just a box — independent of the transcript, no Add button, no live list.
                    // Sent alongside the transcript as a separate field once summarization
                    // exists; not merged into one stream beforehand.
                    OutlinedTextField(
                        value = typedNotes,
                        onValueChange = { viewModel.updateTypedNotes(it) },
                        modifier = Modifier.fillMaxSize(),
                        label = { Text("Notes") },
                    )
                }

                DictationTab.SUMMARY -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Summarize") }
                    Text(
                        "Summarization isn't implemented yet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    Column(modifier = Modifier.weight(1f)) {
                        Text(summaryText ?: "No summary yet.", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedTextField(
                        value = saveTitle,
                        onValueChange = { saveTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.saveTranscriptToOneDrive(saveTitle) },
                            modifier = Modifier.weight(1f),
                            enabled = saveTitle.isNotBlank() && transcriptEntries.isNotEmpty()
                        ) { Text("Transcript") }
                        Button(
                            onClick = { viewModel.saveSummaryToOneDrive(saveTitle) },
                            modifier = Modifier.weight(1f),
                            enabled = saveTitle.isNotBlank() && !summaryText.isNullOrBlank()
                        ) { Text("Summary") }
                        Button(
                            onClick = { viewModel.saveBothToOneDrive(saveTitle) },
                            modifier = Modifier.weight(1f),
                            enabled = saveTitle.isNotBlank() && !summaryText.isNullOrBlank() && transcriptEntries.isNotEmpty()
                        ) { Text("Both") }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear transcript and notes?") },
            text = { Text("This deletes the current transcript and notes. It can't be undone.") },
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
private fun TranscriptEntryRow(entry: TranscriptEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "[${entry.timestamp}] ${entry.speakerId}: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = entry.text, style = MaterialTheme.typography.bodySmall)
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
