package com.nabla.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.nabla.notes.model.FileKind
import com.nabla.notes.ui.browser.FileBrowserScreen
import com.nabla.notes.ui.editor.EditorScreen
import com.nabla.notes.ui.settings.SettingsScreen
import com.nabla.notes.ui.viewer.ImageViewerScreen
import com.nabla.notes.ui.viewer.PdfViewerScreen
import com.nabla.notes.viewmodel.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nabla.notes.model.NoteFile
import com.nabla.notes.ui.theme.NotepadTheme
import com.google.gson.Gson
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialNoteJson = intent.getStringExtra("open_note_json")
        setContent {
            NotepadTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                val isExpandedOrMedium = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                if (isExpandedOrMedium) {
                    SplitPaneLayout(initialNoteJson)
                } else {
                    SinglePaneLayout(initialNoteJson)
                }
            }
        }
    }
}

// ─── Shared routing helpers ───────────────────────────────────────────────────

private fun encodeFile(file: NoteFile, gson: Gson): String =
    URLEncoder.encode(gson.toJson(file), StandardCharsets.UTF_8.name())

private fun decodeFile(fileJson: String, gson: Gson): NoteFile =
    gson.fromJson(URLDecoder.decode(fileJson, StandardCharsets.UTF_8.name()), NoteFile::class.java)

/** Navigate to the appropriate full-screen viewer/editor route for [file]'s kind. */
private fun NavController.navigateToFile(file: NoteFile, gson: Gson) {
    val json = encodeFile(file, gson)
    when (file.kind) {
        FileKind.MARKDOWN, FileKind.TEXT -> navigate("editor/$json")
        FileKind.IMAGE -> navigate("imageViewer/$json")
        FileKind.PDF -> navigate("pdfViewer/$json")
        FileKind.OTHER -> { /* unsupported file type — no-op */ }
    }
}

/** Navigate to a viewer/editor from a resolved in-note link, without a full NoteFile. */
private fun NavController.navigateToFile(id: String, kind: FileKind, name: String, gson: Gson) {
    navigateToFile(NoteFile(id = id, name = name), gson)
}

// ─── Single-Pane Navigation (phones) ─────────────────────────────────────────

@Composable
private fun SinglePaneLayout(initialNoteJson: String? = null) {
    val navController = rememberNavController()
    val gson = Gson()

    val startDestination = if (initialNoteJson != null) {
        val encoded = URLEncoder.encode(initialNoteJson, StandardCharsets.UTF_8.name())
        "editor/$encoded"
    } else "browser"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("browser") {
            val viewModel: BrowserViewModel = hiltViewModel()
            FileBrowserScreen(
                viewModel = viewModel,
                onFileSelected = { file ->
                    navController.navigateToFile(file, gson)
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }

        composable("editor/{fileJson}") { backStackEntry ->
            val fileJson = backStackEntry.arguments?.getString("fileJson") ?: return@composable
            val noteFile = decodeFile(fileJson, gson)
            EditorScreen(
                noteFile = noteFile,
                showBack = true,
                onBackClick = { navController.popBackStack() },
                onOpenFile = { id, kind, name -> navController.navigateToFile(id, kind, name, gson) }
            )
        }

        composable("imageViewer/{fileJson}") { backStackEntry ->
            val fileJson = backStackEntry.arguments?.getString("fileJson") ?: return@composable
            val noteFile = decodeFile(fileJson, gson)
            ImageViewerScreen(
                noteFile = noteFile,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("pdfViewer/{fileJson}") { backStackEntry ->
            val fileJson = backStackEntry.arguments?.getString("fileJson") ?: return@composable
            val noteFile = decodeFile(fileJson, gson)
            PdfViewerScreen(
                noteFile = noteFile,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

// ─── Split-Pane Layout (tablets / foldables) ─────────────────────────────────

@Composable
private fun SplitPaneLayout(initialNoteJson: String? = null) {
    val browserViewModel: BrowserViewModel = hiltViewModel()
    val selectedFile by browserViewModel.selectedFile.collectAsState()
    val navController = rememberNavController()
    val gson = Gson()

    LaunchedEffect(initialNoteJson) {
        if (initialNoteJson != null) {
            val noteFile = Gson().fromJson(initialNoteJson, NoteFile::class.java)
            browserViewModel.selectFile(noteFile)
        }
    }

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left pane — file browser (fixed 360dp)
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                ) {
                    FileBrowserScreen(
                        viewModel = browserViewModel,
                        onFileSelected = { file ->
                            when (file.kind) {
                                FileKind.MARKDOWN, FileKind.TEXT -> browserViewModel.selectFile(file)
                                else -> navController.navigateToFile(file, gson)
                            }
                        },
                        onSettingsClick = {
                            navController.navigate("settings")
                        }
                    )
                }

                // Right pane — editor (fills remaining space)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val file = selectedFile
                    if (file != null) {
                        EditorScreen(
                            noteFile = file,
                            showBack = false,
                            onBackClick = { browserViewModel.clearSelectedFile() },
                            onOpenFile = { id, kind, name -> navController.navigateToFile(id, kind, name, gson) }
                        )
                    }
                }
            }
        }

        composable("imageViewer/{fileJson}") { backStackEntry ->
            val fileJson = backStackEntry.arguments?.getString("fileJson") ?: return@composable
            val noteFile = decodeFile(fileJson, gson)
            ImageViewerScreen(
                noteFile = noteFile,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("pdfViewer/{fileJson}") { backStackEntry ->
            val fileJson = backStackEntry.arguments?.getString("fileJson") ?: return@composable
            val noteFile = decodeFile(fileJson, gson)
            PdfViewerScreen(
                noteFile = noteFile,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
