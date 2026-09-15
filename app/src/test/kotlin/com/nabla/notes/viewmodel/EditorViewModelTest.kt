package com.nabla.notes.viewmodel

import android.app.Activity
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nabla.notes.model.NoteFile
import com.nabla.notes.repository.OneDriveRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = EditorViewModel(context, oneDriveRepository)
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
}
