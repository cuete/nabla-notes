package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.repository.SettingsRepository
import com.nabla.notes.summarizer.Summarizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers only insertDictatedText (P4: dictate-at-cursor in the editor) — EditorViewModel had
 * no existing test coverage to extend; backfilling the rest is out of scope here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val oneDriveRepository = mockk<OneDriveRepository>(relaxed = true)
    private val summarizer = mockk<Summarizer>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
        every { editorFontSize } returns flowOf(SettingsRepository.DEFAULT_EDITOR_FONT_SIZE)
    }
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = EditorViewModel(context, oneDriveRepository, summarizer, settingsRepository)
        viewModel.setContent("hello world")
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `insertDictatedText inserts at cursor with a trailing space and advances the cursor past it`() {
        // Cursor after "hello " (position 6), before "world"
        viewModel.updateTextFieldValue(TextFieldValue("hello world", TextRange(6)), activity)

        viewModel.insertDictatedText("there", activity)

        val result = viewModel.textFieldValue.value
        assertEquals("hello there world", result.text)
        assertEquals(TextRange(12), result.selection) // right after "there "
    }

    @Test
    fun `insertDictatedText replaces an active selection`() {
        // Select "world" (positions 6..11)
        viewModel.updateTextFieldValue(TextFieldValue("hello world", TextRange(6, 11)), activity)

        viewModel.insertDictatedText("there", activity)

        assertEquals("hello there ", viewModel.textFieldValue.value.text)
    }

    @Test
    fun `insertDictatedText with blank text is a no-op`() {
        val before = viewModel.textFieldValue.value

        viewModel.insertDictatedText("   ", activity)

        assertEquals(before, viewModel.textFieldValue.value)
    }

    @Test
    fun `insertDictatedText schedules an autosave`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val file = NoteFile(id = "note1", name = "test.md", lastModified = "")
        coEvery { oneDriveRepository.downloadFileContent("note1", activity) } returns Result.success("hello world")
        coEvery { oneDriveRepository.saveFileContent("note1", any(), activity) } returns Result.success(Unit)

        viewModel.loadFile(file, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.insertDictatedText("there", activity)
        testDispatcher.scheduler.advanceTimeBy(2001L)
        testDispatcher.scheduler.runCurrent()

        // setContent() (via loadFile's download) resets the cursor to position 0, so the
        // insert lands at the start — not the "hello " midpoint the other tests use.
        coVerify { oneDriveRepository.saveFileContent("note1", "there hello world", activity) }
    }

    @Test
    fun `flushSave saves immediately without waiting for the autosave debounce`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val file = NoteFile(id = "note1", name = "test.md", lastModified = "")
        coEvery { oneDriveRepository.downloadFileContent("note1", activity) } returns Result.success("hello")
        coEvery { oneDriveRepository.saveFileContent("note1", any(), activity) } returns Result.success(Unit)
        viewModel.loadFile(file, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateTextFieldValue(TextFieldValue("hello edited", TextRange(12)), activity)
        viewModel.flushSave(activity)
        testDispatcher.scheduler.runCurrent() // no advanceTimeBy: the 2s debounce must not be needed

        coVerify(exactly = 1) { oneDriveRepository.saveFileContent("note1", "hello edited", activity) }
    }

    @Test
    fun `flushSave does nothing when there are no unsaved changes`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val file = NoteFile(id = "note1", name = "test.md", lastModified = "")
        coEvery { oneDriveRepository.downloadFileContent("note1", activity) } returns Result.success("hello")
        viewModel.loadFile(file, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.flushSave(activity)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { oneDriveRepository.saveFileContent(any(), any(), any()) }
    }

    @Test
    fun `first inline dictation insert is timestamped on its own line, later ones are not`() {
        viewModel.updateTextFieldValue(TextFieldValue("hello world", TextRange(11)), activity)
        viewModel.beginInlineDictation()

        viewModel.insertDictatedText("one", activity)
        viewModel.insertDictatedText("two", activity)

        val text = viewModel.textFieldValue.value.text
        assertTrue(text, Regex("""hello world
\[\d{4}-\d\d-\d\d \d\d:\d\d:\d\d] one two """).matches(text))
    }

    @Test
    fun `inline dictation at the start of a line gets a timestamp without an extra newline`() {
        viewModel.updateTextFieldValue(TextFieldValue("hello world", TextRange(0)), activity)
        viewModel.beginInlineDictation()

        viewModel.insertDictatedText("one", activity)

        val text = viewModel.textFieldValue.value.text
        assertTrue(text, Regex("""\[\d{4}-\d\d-\d\d \d\d:\d\d:\d\d] one hello world""").matches(text))
    }

    @Test
    fun `organize then apply replaces the note and is undoable`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        coEvery { summarizer.organize("hello world") } returns Result.success("```markdown\n# Hello\nworld\n```")

        viewModel.organize()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OrganizeState.Ready("# Hello\nworld"), viewModel.organizeState.value)

        viewModel.applyOrganized(activity)
        assertEquals("# Hello\nworld", viewModel.textFieldValue.value.text)
        assertEquals(OrganizeState.Idle, viewModel.organizeState.value)

        viewModel.undo()
        assertEquals("hello world", viewModel.textFieldValue.value.text)
    }
}
