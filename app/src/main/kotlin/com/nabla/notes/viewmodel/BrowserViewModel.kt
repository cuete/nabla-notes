package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.notes.auth.MsalManager
import com.nabla.notes.model.AppSettings
import com.nabla.notes.model.BrowserEntry
import com.nabla.notes.model.FolderItem
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the file browser screen. */
sealed class BrowserUiState {
    object Idle : BrowserUiState()
    object Loading : BrowserUiState()
    object SignInRequired : BrowserUiState()
    data class Success(val entries: List<BrowserEntry>) : BrowserUiState()
    data class Error(val message: String) : BrowserUiState()
}

/**
 * ViewModel for the file browser.
 *
 * Responsibilities:
 *  - Load the list of .txt/.md files from OneDrive
 *  - Track the currently selected file (used for split-pane mode)
 *  - Create new files
 *  - Expose current settings (folder ID)
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val msalManager: MsalManager,
    private val oneDriveRepository: OneDriveRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<BrowserUiState>(BrowserUiState.Idle)
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Currently selected file (drives editor in split-pane mode). */
    private val _selectedFile = MutableStateFlow<NoteFile?>(null)
    val selectedFile: StateFlow<NoteFile?> = _selectedFile.asStateFlow()

    /** Current settings exposed to the UI. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    /**
     * Navigation stack for subfolder browsing.
     * Each entry is a (folderId, folderName) pair.
     * Empty list means we're at the configured root folder.
     */
    private val _folderStack = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val folderStack: StateFlow<List<Pair<String, String>>> = _folderStack.asStateFlow()

    init {
        // Restore last folder stack from DataStore on launch.
        viewModelScope.launch {
            val savedStack = settingsRepository.loadLastFolderStack()
            if (savedStack.isNotEmpty()) {
                _folderStack.value = savedStack
            }
        }
        // Persist stack whenever it changes so the next launch can restore it.
        viewModelScope.launch {
            _folderStack.collect { stack ->
                settingsRepository.saveLastFolderStack(stack)
            }
        }
    }

    /**
     * Breadcrumb path derived from the folder stack and settings.
     * Example: "OneDrive / Notes / 2026"
     */
    val currentPath: StateFlow<String> = combine(_folderStack, settings) { stack, s ->
        val root = if (s.folderPath.isBlank() || s.folderPath == "/") "OneDrive"
                   else s.folderPath.trimStart('/')
        if (stack.isEmpty()) root
        else "$root / " + stack.joinToString(" / ") { it.second }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "OneDrive")

    // ─── Public actions ──────────────────────────────────────────────────────────

    /**
     * Load or refresh the mixed folder+file list from OneDrive.
     * Uses the current folder stack to determine which folder to list.
     * Handles MSAL initialization and auth state automatically.
     */
    fun loadFiles(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = BrowserUiState.Loading

            // Ensure MSAL is initialized
            msalManager.initialize().onFailure { e ->
                _uiState.value = BrowserUiState.Error("Auth init failed: ${e.message}")
                return@launch
            }

            // Check if user is signed in
            val account = msalManager.getCurrentAccount()
            if (account == null) {
                _uiState.value = BrowserUiState.SignInRequired
                return@launch
            }

            val currentSettings = settings.value
            // Use the top of the folder stack, or fall back to the configured root
            val folderId = _folderStack.value.lastOrNull()?.first ?: currentSettings.folderId

            oneDriveRepository.listFolderContents(
                folderId = folderId,
                folderPath = currentFolderPath(),
                activity = activity
            ).fold(
                onSuccess = { entries -> _uiState.value = BrowserUiState.Success(entries) },
                onFailure = { e -> _uiState.value = BrowserUiState.Error(e.message ?: "Unknown error") }
            )
        }
    }

    /**
     * Navigate into a subfolder: push it onto the stack and reload.
     */
    fun navigateIntoFolder(folder: FolderItem, activity: Activity) {
        _folderStack.value = _folderStack.value + (folder.id to folder.name)
        loadFiles(activity)
    }

    /**
     * Navigate up one level: pop the stack and reload.
     */
    fun navigateUp(activity: Activity) {
        _folderStack.value = _folderStack.value.dropLast(1)
        loadFiles(activity)
    }

    /**
     * Returns true when at the configured root (no subfolder navigation in progress).
     */
    fun isAtRoot(): Boolean = _folderStack.value.isEmpty()

    /**
     * OneDrive-root-relative path of the folder currently being browsed, combining the
     * configured root folder path with any subfolder stack navigation.
     */
    fun currentFolderPath(): String {
        val base = settings.value.folderPath
        if (_folderStack.value.isEmpty()) return base
        val subPath = _folderStack.value.joinToString("/") { it.second }
        return if (base.isBlank()) subPath else "$base/$subPath"
    }

    /**
     * Trigger interactive sign-in then reload files.
     */
    fun signIn(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = BrowserUiState.Loading
            msalManager.initialize().onFailure { e ->
                _uiState.value = BrowserUiState.Error("Auth init failed: ${e.message}")
                return@launch
            }
            msalManager.signIn(activity).fold(
                onSuccess = { loadFiles(activity) },
                onFailure = { e -> _uiState.value = BrowserUiState.Error(e.message ?: "Sign-in failed") }
            )
        }
    }

    /**
     * Set the currently selected file (for split-pane view).
     */
    fun selectFile(file: NoteFile) {
        _selectedFile.value = file
    }

    /**
     * Create a new file in the currently active folder (respects subfolder navigation).
     *
     * @param name     File name without extension (extension added based on [isMarkdown]).
     * @param isMarkdown  If true, creates .md; otherwise .txt.
     * @param activity Activity for auth.
     * @param onCreated Callback with the new NoteFile on success.
     */
    fun createFile(
        name: String,
        isMarkdown: Boolean,
        activity: Activity,
        onCreated: (NoteFile) -> Unit
    ) {
        viewModelScope.launch {
            val extension = if (isMarkdown) ".md" else ".txt"
            val fileName = if (name.endsWith(extension)) name else "$name$extension"

            oneDriveRepository.createFile(
                folderPath = currentFolderPath(),
                fileName = fileName,
                activity = activity
            ).fold(
                onSuccess = { newFile ->
                    // Refresh file list and select new file
                    loadFiles(activity)
                    _selectedFile.value = newFile
                    onCreated(newFile)
                },
                onFailure = { e ->
                    _uiState.value = BrowserUiState.Error("Create failed: ${e.message}")
                }
            )
        }
    }

    /**
     * Clear the selected file (e.g. when navigating back from editor in split-pane).
     */
    fun clearSelectedFile() {
        _selectedFile.value = null
    }

    /**
     * Permanently delete a file from OneDrive, then refresh the file list.
     *
     * @param noteFile  The [NoteFile] to delete.
     * @param activity  Activity for auth.
     */
    fun deleteFile(noteFile: NoteFile, activity: Activity) {
        viewModelScope.launch {
            oneDriveRepository.deleteFile(noteFile.id, activity).fold(
                onSuccess = {
                    // If the deleted file was selected, clear selection
                    if (_selectedFile.value?.id == noteFile.id) {
                        _selectedFile.value = null
                    }
                    loadFiles(activity)
                },
                onFailure = { e ->
                    _uiState.value = BrowserUiState.Error("Delete failed: ${e.message}")
                }
            )
        }
    }
}
