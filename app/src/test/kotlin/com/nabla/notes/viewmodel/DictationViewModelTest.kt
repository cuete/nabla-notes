package com.nabla.notes.viewmodel

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.nabla.notes.model.AppSettings
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
        coEvery { dictationRepository.pendingTextFoldedCount() } returns 0
        coEvery { dictationRepository.pendingText() } returns ""
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
    fun `clearTranscript clears entries and calls DictationRepository clearSession`() {
        viewModel.clearTranscript()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<TranscriptEntry>(), viewModel.transcriptEntries.value)
        coVerify { dictationRepository.clearSession() }
    }

    // --- Notes: typed input + pending buffer ---

    @Test
    fun `addTypedText appends trimmed text to pendingText with typed marker`() {
        viewModel.addTypedText("  hello world  ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("📝 hello world", viewModel.pendingText.value)
        coVerify { dictationRepository.savePendingText("📝 hello world") }
    }

    @Test
    fun `addTypedText with blank input is a no-op`() {
        viewModel.addTypedText("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.pendingText.value)
        coVerify(exactly = 0) { dictationRepository.savePendingText(any()) }
    }

    @Test
    fun `consecutive entries within the pause window each get their own line`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.addTypedText("first")
        now += 5_000L
        viewModel.addTypedText("second")

        assertEquals("📝 first\n📝 second", viewModel.pendingText.value)
    }

    @Test
    fun `a 60-second pause inserts an extra blank line before the next entry`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.addTypedText("first")
        now += DictationViewModel.PENDING_LINE_BREAK_GAP_MS
        viewModel.addTypedText("second")

        assertEquals("📝 first\n\n📝 second", viewModel.pendingText.value)
    }

    @Test
    fun `voice and typed entries each get their own marked line`() {
        var now = 1_000_000L
        viewModel.clockMs = { now }

        viewModel.appendPendingText("hola", typed = false)
        now += 5_000L
        viewModel.appendPendingText("typed note", typed = true)
        now += 5_000L
        viewModel.appendPendingText("más voz", typed = false)

        assertEquals("🎙 hola\n📝 typed note\n🎙 más voz", viewModel.pendingText.value)
    }

    @Test
    fun `clearPendingText resets pending text`() {
        viewModel.addTypedText("hello world")

        viewModel.clearPendingText()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.pendingText.value)
    }

    // --- Save ---

    @Test
    fun `saveNotesToOneDrive is a no-op when pending text is blank`() {
        viewModel.saveNotesToOneDrive("title")
        confirmVerified(oneDriveRepository)
    }

    @Test
    fun `saveNotesToOneDrive strips source markers, creates then writes the file, in the configured folder`() {
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
