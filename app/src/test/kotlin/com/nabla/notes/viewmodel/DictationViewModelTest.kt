package com.nabla.notes.viewmodel

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.nabla.notes.model.AppSettings
import com.nabla.notes.model.NoteLine
import com.nabla.notes.repository.DictationRepository
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.repository.SettingsRepository
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.TranscriptionService
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors DictationViewModel's private formatter, so tests can compute the same timestamps. */
private val testTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * Ported from nabla-chato-voice's DictationViewModelTest. Dropped: every organizeNotes/
 * summarize test — that feature wasn't ported (see DictationViewModel's class doc). Dropped
 * the gatewayRepository mock for the same reason. Added: OneDriveRepository/SettingsRepository
 * mocks for the save path, and DictationRepository's methods are all suspend (unlike chato's
 * synchronous ChatoGatewayRepository), so setup uses coEvery instead of every throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: DictationViewModel
    private val context = mockk<Context>(relaxed = true)
    private val dictationRepository = mockk<DictationRepository>(relaxed = true)
    private val oneDriveRepository = mockk<OneDriveRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { dictationRepository.load() } returns emptyList()
        coEvery { dictationRepository.noteLinesFoldedCount() } returns 0
        coEvery { dictationRepository.noteLines() } returns emptyList()
        coEvery { dictationRepository.contextNotes() } returns ""
        coEvery { dictationRepository.azureSpeechKey() } returns ""
        coEvery { dictationRepository.azureSpeechRegion() } returns "eastus"
        every { settingsRepository.settings } returns flowOf(AppSettings(folderPath = "Notes", folderId = "root"))
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.nabla.notes"
        // init's passive reattach check must not find a running service by default — individual
        // tests that want a fresh startSession() to succeed re-stub this with flags=AUTO_CREATE.
        every { context.bindService(any(), any(), any<Int>()) } returns false

        viewModel = DictationViewModel(context, dictationRepository, oneDriveRepository, settingsRepository)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    // --- Session state ---

    @Test
    fun `initial state is Idle`() {
        assertEquals(DictationSessionState.Idle, viewModel.state.value)
    }

    @Test
    fun `default mode is Notes`() {
        assertEquals(com.nabla.voice.DictationMode.NOTES, viewModel.mode.value)
    }

    @Test
    fun `startSession sets state to Recording immediately`() {
        // startSession() reads the cached settings snapshot (it's not itself suspend, so it
        // can't re-fetch async) — saveAzureSettings both persists and refreshes that cache.
        coEvery { dictationRepository.saveAzureSettings("test-key", "eastus") } just Runs
        coEvery { dictationRepository.azureSpeechKey() } returns "test-key"
        every { context.bindService(any(), any(), any<Int>()) } returns true
        every { context.startForegroundService(any()) } returns mockk()
        viewModel.saveAzureSettings("test-key", "eastus")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startSession("meeting notes")

        assertEquals(DictationSessionState.Recording, viewModel.state.value)
    }

    @Test
    fun `startSession with blank azure key sets Error state`() {
        viewModel.startSession()

        assertTrue(viewModel.state.value is DictationSessionState.Error)
    }

    @Test
    fun `setMode is ignored while Recording`() {
        coEvery { dictationRepository.azureSpeechKey() } returns "test-key"
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns true
        every { context.startForegroundService(any()) } returns mockk()
        viewModel.saveAzureSettings("test-key", "eastus")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startSession()
        viewModel.setMode(com.nabla.voice.DictationMode.CONVERSATION)

        assertEquals(com.nabla.voice.DictationMode.NOTES, viewModel.mode.value)
    }

    // --- Reattach to an already-running service (mirrors chato's P1 coverage) ---

    @Test
    fun `init passively checks for an already-running service without creating one`() {
        // flags=0, not BIND_AUTO_CREATE — must not spin up a new service just to check.
        verify { context.bindService(any(), any(), 0) }
    }

    @Test
    fun `init reattaches to an already-running service, restoring its transcript and Recording state`() {
        val existingEntries = listOf(TranscriptEntry("10:00:01", "You", "hello from before"))
        val mockService = mockk<TranscriptionService>(relaxed = true)
        every { mockService.transcriptEntries } returns MutableStateFlow(existingEntries)
        every { mockService.isRecording } returns MutableStateFlow(true)
        every { mockService.error } returns MutableSharedFlow()

        val binder = mockk<TranscriptionService.TranscriptionBinder>()
        every { binder.getService() } returns mockService

        every { context.bindService(any(), any(), 0) } answers {
            val connection = secondArg<ServiceConnection>()
            connection.onServiceConnected(mockk(relaxed = true), binder)
            true
        }

        val reattached = DictationViewModel(context, dictationRepository, oneDriveRepository, settingsRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DictationSessionState.Recording, reattached.state.value)
        assertEquals(existingEntries, reattached.transcriptEntries.value)
    }

    @Test
    fun `clearTranscript clears entries, note lines, and calls DictationRepository clearSession`() {
        viewModel.addTypedText("hello world")

        viewModel.clearTranscript()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<TranscriptEntry>(), viewModel.transcriptEntries.value)
        // dictationRepository.clearSession() wipes the persisted note lines too — the
        // in-memory flow has to match, or clearing leaves stale text on screen (regression
        // caught 2026-09-15: clearTranscript only reset transcriptEntries, not the pending
        // buffer).
        assertEquals(emptyList<NoteLine>(), viewModel.noteLines.value)
        coVerify { dictationRepository.clearSession() }
    }

    // --- Notes: typed input + unified note-lines stream ---

    @Test
    fun `addTypedText appends a typed NoteLine with no speaker`() {
        val now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.addTypedText("  hello world  ")
        testDispatcher.scheduler.advanceUntilIdle()

        val expected = NoteLine(testTimeFormat.format(Date(now)), speakerId = null, text = "hello world", typed = true)
        assertEquals(listOf(expected), viewModel.noteLines.value)
        coVerify { dictationRepository.saveNoteLines(listOf(expected)) }
    }

    @Test
    fun `addTypedText with blank input is a no-op`() {
        viewModel.addTypedText("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<NoteLine>(), viewModel.noteLines.value)
        coVerify(exactly = 0) { dictationRepository.saveNoteLines(any()) }
    }

    @Test
    fun `consecutive entries within the pause window are not marked as gapped`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.addTypedText("first")
        now += 5_000L
        viewModel.addTypedText("second")

        assertEquals(listOf(false, false), viewModel.noteLines.value.map { it.precededByGap })
        assertEquals(listOf("first", "second"), viewModel.noteLines.value.map { it.text })
    }

    @Test
    fun `a 60-second pause marks the next entry as precededByGap`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.addTypedText("first")
        now += DictationViewModel.PENDING_LINE_BREAK_GAP_MS
        viewModel.addTypedText("second")

        assertEquals(listOf(false, true), viewModel.noteLines.value.map { it.precededByGap })
    }

    @Test
    fun `spoken and typed entries interleave, each keeping their own speaker and timestamp`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.appendNoteLine(timestamp = "10:00:00", speakerId = "You", text = "hola", typed = false)
        now += 5_000L
        viewModel.appendNoteLine(timestamp = "10:00:05", speakerId = null, text = "typed note", typed = true)
        now += 5_000L
        viewModel.appendNoteLine(timestamp = "10:00:10", speakerId = "You", text = "más voz", typed = false)

        assertEquals(
            listOf(
                NoteLine("10:00:00", "You", "hola", typed = false),
                NoteLine("10:00:05", null, "typed note", typed = true),
                NoteLine("10:00:10", "You", "más voz", typed = false),
            ),
            viewModel.noteLines.value
        )
    }

    // --- Save ---

    @Test
    fun `saveNotesToOneDrive is a no-op when there are no note lines`() {
        viewModel.saveNotesToOneDrive("title")
        confirmVerified(oneDriveRepository)
    }

    @Test
    fun `saveNotesToOneDrive joins note line text plainly, creates then writes the file, in the configured folder`() {
        val activity = mockk<android.app.Activity>(relaxed = true)
        viewModel.setActivity(activity)
        viewModel.addTypedText("hello world")

        val newFile = com.nabla.notes.model.NoteFile(id = "abc123", name = "title.md", lastModified = "")
        coEvery { oneDriveRepository.createFile("Notes", "title.md", activity) } returns Result.success(newFile)
        coEvery { oneDriveRepository.saveFileContent("abc123", "hello world", activity) } returns Result.success(Unit)

        viewModel.saveNotesToOneDrive("title")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { oneDriveRepository.createFile("Notes", "title.md", activity) }
        coVerify { oneDriveRepository.saveFileContent("abc123", "hello world", activity) }
        assertEquals("Saved to Notes/title.md", viewModel.saveStatus.value)
    }
}
