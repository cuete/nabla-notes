package com.nabla.notes.ui.tracker

import android.app.Activity
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.nabla.notes.MainActivity
import com.nabla.notes.auth.MsalManager
import com.nabla.notes.markdown.flipTaskCheckbox
import com.nabla.notes.markdown.normalizeBareTaskLines
import com.nabla.notes.markdown.taskListTogglePlugin
import com.nabla.notes.model.BrowserEntry
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.repository.SettingsRepository
import com.nabla.notes.ui.theme.NotepadTheme
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private sealed class TrackerState {
    object Loading : TrackerState()
    data class Ready(val content: String, val noteFile: NoteFile) : TrackerState()
    data class Error(val message: String) : TrackerState()
}

@AndroidEntryPoint
class TrackerPopupActivity : ComponentActivity() {

    @Inject lateinit var msalManager: MsalManager
    @Inject lateinit var oneDriveRepository: OneDriveRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotepadTheme {
                TrackerBottomSheet(
                    msalManager = msalManager,
                    oneDriveRepository = oneDriveRepository,
                    settingsRepository = settingsRepository,
                    activity = this,
                    onDismiss = ::finish
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerBottomSheet(
    msalManager: MsalManager,
    oneDriveRepository: OneDriveRepository,
    settingsRepository: SettingsRepository,
    activity: Activity,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var state by remember { mutableStateOf<TrackerState>(TrackerState.Loading) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Optimistically flips the ordinal-th checkbox and persists it back to OneDrive.
    // Best-effort: on failure the UI keeps the toggled state (matches the editor's silent
    // autosave — no inline error surface for this quick widget-popup interaction).
    val onToggleTask: (Int) -> Unit = onToggle@{ ordinal ->
        val readyState = state as? TrackerState.Ready ?: return@onToggle
        val newContent = flipTaskCheckbox(readyState.content, ordinal)
        if (newContent == readyState.content) return@onToggle
        state = readyState.copy(content = newContent)
        coroutineScope.launch {
            oneDriveRepository.saveFileContent(readyState.noteFile.id, newContent, activity)
                .onFailure { e ->
                    android.util.Log.e("NablaNotes", "Failed to save tracker.md toggle", e)
                }
        }
    }

    LaunchedEffect(Unit) {
        msalManager.initialize().onFailure {
            state = TrackerState.Error("Could not initialize auth — open the app first")
            return@LaunchedEffect
        }

        // Silent-only: don't launch browser from the widget popup
        val tokenResult = msalManager.acquireTokenSilentOnly()
        if (tokenResult.isFailure) {
            state = TrackerState.Error("Open the app and sign in first")
            return@LaunchedEffect
        }

        val currentSettings = settingsRepository.settings.first()
        val folderId = currentSettings.folderId

        val entries = oneDriveRepository.listFolderContents(folderId, currentSettings.folderPath, activity).getOrNull()
        val trackerFile = entries
            ?.filterIsInstance<BrowserEntry.File>()
            ?.find { it.note.name.equals("tracker.md", ignoreCase = true) }
            ?.note

        if (trackerFile == null) {
            state = TrackerState.Error("tracker.md not found in the configured notes folder")
            return@LaunchedEffect
        }

        oneDriveRepository.downloadFileContent(trackerFile.id, activity).fold(
            onSuccess = { content -> state = TrackerState.Ready(content, trackerFile) },
            onFailure = { e -> state = TrackerState.Error(e.message ?: "Failed to load tracker.md") }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        val readyState = state as? TrackerState.Ready
        if (readyState != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = readyState.noteFile.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("open_note_json", Gson().toJson(readyState.noteFile))
                    }
                    context.startActivity(intent)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit in app"
                    )
                }
            }
        }

        when (val s = state) {
            is TrackerState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is TrackerState.Ready -> TrackerContent(content = s.content, onToggleTask = onToggleTask)

            is TrackerState.Error -> Text(
                text = s.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(24.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun TrackerContent(content: String, onToggleTask: (Int) -> Unit) {
    val context = LocalContext.current
    val taskCounter = remember { AtomicInteger(0) }
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(taskListTogglePlugin(taskCounter, onToggleTask))
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .navigationBarsPadding()
    ) {
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    textSize = 15f
                }
            },
            update = { tv ->
                taskCounter.set(0)
                markwon.setMarkdown(tv, normalizeBareTaskLines(content))
                tv.setTextIsSelectable(true)
                tv.movementMethod = LinkMovementMethod.getInstance()
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
