package com.nabla.notes.viewmodel

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.nabla.notes.repository.DictationRepository
import com.nabla.notes.repository.OneDriveRepository
import com.nabla.notes.summarizer.Summarizer
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.TranscriptionService
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 *
 * 2026-09-16: dropped the unified NoteLine model an earlier round introduced — typedNotes is
 * back to being an independent plain string (Notes tab is "just a box"), transcriptEntries is
 * the service's plain list again, no fold/interleave logic to test anymore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: DictationViewModel
    private val context = mockk<Context>(relaxed = true)
    private val dictationRepository = mockk<DictationRepository>(relaxed = true)
    private val oneDriveRepository = mockk<OneDriveRepository>(relaxed = true)
    private val summarizer = mockk<Summarizer>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { dictationRepository.load() } returns emptyList()
        coEvery { dictationRepository.typedNotes() } returns ""
        coEvery { dictationRepository.contextNotes() } returns ""
        coEvery { dictationRepository.azureSpeechKey() } returns ""
        coEvery { dictationRepository.azureSpeechRegion() } returns "eastus"
        coEvery { dictationRepository.gatewayUrl() } returns ""
        coEvery { dictationRepository.gatewayToken() } returns ""
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.nabla.notes"
        // init's passive reattach check must not find a running service by default — individual
        // tests that want a fresh startSession() to succeed re-stub this with flags=AUTO_CREATE.
        every { context.bindService(any(), any(), any<Int>()) } returns false

        viewModel = DictationViewModel(context, dictationRepository, oneDriveRepository, summarizer)
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
        every { mockService.inlineUtterances } returns MutableSharedFlow()

        val binder = mockk<TranscriptionService.TranscriptionBinder>()
        every { binder.getService() } returns mockService

        every { context.bindService(any(), any(), 0) } answers {
            val connection = secondArg<ServiceConnection>()
            connection.onServiceConnected(mockk(relaxed = true), binder)
            true
        }

        val reattached = DictationViewModel(context, dictationRepository, oneDriveRepository, summarizer)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DictationSessionState.Recording, reattached.state.value)
        assertEquals(existingEntries, reattached.transcriptEntries.value)
    }

    @Test
    fun `inline utterances are forwarded on their own flow and never enter the transcript`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val inline = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val mockService = mockk<TranscriptionService>(relaxed = true)
        every { mockService.transcriptEntries } returns MutableStateFlow(emptyList())
        every { mockService.isRecording } returns MutableStateFlow(true)
        every { mockService.error } returns MutableSharedFlow()
        every { mockService.inlineUtterances } returns inline

        val binder = mockk<TranscriptionService.TranscriptionBinder>()
        every { binder.getService() } returns mockService
        every { context.bindService(any(), any(), 0) } answers {
            secondArg<ServiceConnection>().onServiceConnected(mockk(relaxed = true), binder)
            true
        }

        val vm = DictationViewModel(context, dictationRepository, oneDriveRepository, summarizer)
        val received = mutableListOf<String>()
        val job = launch { vm.inlineUtterances.collect { received.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        inline.emit("hello inline")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello inline"), received)
        assertEquals(emptyList<TranscriptEntry>(), vm.transcriptEntries.value)
        job.cancel()
    }

    @Test
    fun `clearTranscript clears entries, typed notes, and calls DictationRepository clearSession`() {
        viewModel.updateTypedNotes("hello world")

        viewModel.clearTranscript()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<TranscriptEntry>(), viewModel.transcriptEntries.value)
        assertEquals("", viewModel.typedNotes.value)
        coVerify { dictationRepository.clearSession() }
    }

    // --- Notes tab: just a box ---

    @Test
    fun `updateTypedNotes updates immediately`() {
        viewModel.updateTypedNotes("hello world")

        assertEquals("hello world", viewModel.typedNotes.value)
    }

    @Test
    fun `updateTypedNotes persists on a debounce, not on every keystroke`() {
        viewModel.updateTypedNotes("h")
        viewModel.updateTypedNotes("he")
        viewModel.updateTypedNotes("hel")
        coVerify(exactly = 0) { dictationRepository.saveTypedNotes(any()) }

        testDispatcher.scheduler.advanceTimeBy(1001L)
        testDispatcher.scheduler.runCurrent()

        // Only the final value is persisted — each keystroke cancels the previous pending save.
        coVerify(exactly = 1) { dictationRepository.saveTypedNotes("hel") }
    }

    // --- Save (Summary tab) ---

    @Test
    fun `saveTranscriptToOneDrive is a no-op when there are no transcript entries`() {
        viewModel.saveTranscriptToOneDrive("title")
        confirmVerified(oneDriveRepository)
    }

    @Test
    fun `saveSummaryToOneDrive is a no-op when nothing has been summarized yet`() {
        viewModel.saveSummaryToOneDrive("title")
        confirmVerified(oneDriveRepository)
    }

    @Test
    fun `saveBothToOneDrive is a no-op when nothing has been summarized yet`() {
        viewModel.saveBothToOneDrive("title")
        confirmVerified(oneDriveRepository)
    }

    // --- Summarize (Summary tab, P5) ---

    @Test
    fun `summarize is a no-op with no transcript and no typed notes`() {
        viewModel.summarize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { summarizer.summarize(any(), any()) }
        assertNull(viewModel.summaryText.value)
    }

    @Test
    fun `summarize passes transcript and typed notes separately, not merged`() {
        viewModel.updateTypedNotes("remember to follow up")
        // Stubbing with any() rather than the exact expected values — a concrete-value coEvery
        // here didn't actually apply (fell through to the relaxed default, summaryText stayed
        // null) even though coVerify with those same concrete values independently confirmed
        // the call happened with them. Verify separately below instead, where it's reliable.
        coEvery { summarizer.summarize(any(), any()) } returns Result.success("summary")

        // No transcript entries in this ViewModel instance, but typed notes alone are enough
        // to make summarize() proceed — matches its no-op guard (transcript.isEmpty() &&
        // notes.isBlank()), not requiring both.
        viewModel.summarize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { summarizer.summarize(emptyList(), "remember to follow up") }
        assertEquals("summary", viewModel.summaryText.value)
    }

    @Test
    fun `summarize sets isSummarizing true while in flight, false after`() {
        viewModel.updateTypedNotes("notes")
        coEvery { summarizer.summarize(any(), any()) } coAnswers {
            assertTrue(viewModel.isSummarizing.value)
            Result.success("done")
        }

        assertFalse(viewModel.isSummarizing.value)
        viewModel.summarize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isSummarizing.value)
    }

    @Test
    fun `summarize failure surfaces through error, leaves summaryText null`() {
        viewModel.updateTypedNotes("notes")
        coEvery { summarizer.summarize(any(), any()) } returns Result.failure(RuntimeException("gateway down"))

        viewModel.summarize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.summaryText.value)
        assertTrue(viewModel.error.value?.contains("gateway down") == true)
    }

    @Test
    fun `summarize while already summarizing is a no-op`() {
        viewModel.updateTypedNotes("notes")
        coEvery { summarizer.summarize(any(), any()) } coAnswers {
            // Re-entrant call while the first is still in flight.
            viewModel.summarize()
            Result.success("done")
        }

        viewModel.summarize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { summarizer.summarize(any(), any()) }
    }

    @Test
    fun `saveTranscriptToOneDrive fails clearly when no folder was set, rather than falling back silently`() {
        val activity = mockk<android.app.Activity>(relaxed = true)
        viewModel.setActivity(activity)

        val mockService = mockk<TranscriptionService>(relaxed = true)
        every { mockService.transcriptEntries } returns MutableStateFlow(listOf(TranscriptEntry("10:00:01", "You", "hi")))
        every { mockService.isRecording } returns MutableStateFlow(true)
        every { mockService.error } returns MutableSharedFlow()
        every { mockService.inlineUtterances } returns MutableSharedFlow()
        val binder = mockk<TranscriptionService.TranscriptionBinder>()
        every { binder.getService() } returns mockService
        every { context.bindService(any(), any(), 0) } answers {
            secondArg<ServiceConnection>().onServiceConnected(mockk(relaxed = true), binder)
            true
        }
        val reattached = DictationViewModel(context, dictationRepository, oneDriveRepository, summarizer)
        reattached.setActivity(activity)
        testDispatcher.scheduler.advanceUntilIdle()
        // Deliberately never calling reattached.setSaveFolder(...).

        reattached.saveTranscriptToOneDrive("title")
        testDispatcher.scheduler.advanceUntilIdle()

        confirmVerified(oneDriveRepository)
        assertTrue(reattached.saveStatus.value?.contains("no folder set") == true)
    }

    @Test
    fun `saveTranscriptToOneDrive saves into the folder set by setSaveFolder, not any default`() {
        val activity = mockk<android.app.Activity>(relaxed = true)
        viewModel.setActivity(activity)

        val mockService = mockk<TranscriptionService>(relaxed = true)
        val entries = listOf(TranscriptEntry("10:00:01", "You", "hello world"))
        every { mockService.transcriptEntries } returns MutableStateFlow(entries)
        every { mockService.isRecording } returns MutableStateFlow(true)
        every { mockService.error } returns MutableSharedFlow()
        every { mockService.inlineUtterances } returns MutableSharedFlow()
        val binder = mockk<TranscriptionService.TranscriptionBinder>()
        every { binder.getService() } returns mockService
        every { context.bindService(any(), any(), 0) } answers {
            secondArg<ServiceConnection>().onServiceConnected(mockk(relaxed = true), binder)
            true
        }
        val reattached = DictationViewModel(context, dictationRepository, oneDriveRepository, summarizer)
        reattached.setActivity(activity)
        // The folder the file browser happened to be showing — not any app-wide default.
        reattached.setSaveFolder("Projects/2026")
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedContent = "[10:00:01] You: hello world"
        val newFile = com.nabla.notes.model.NoteFile(id = "abc123", name = "title.md", lastModified = "")
        coEvery { oneDriveRepository.createFile("Projects/2026", "title.md", activity) } returns Result.success(newFile)
        coEvery { oneDriveRepository.saveFileContent("abc123", expectedContent, activity) } returns Result.success(Unit)

        reattached.saveTranscriptToOneDrive("title")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { oneDriveRepository.createFile("Projects/2026", "title.md", activity) }
        coVerify { oneDriveRepository.saveFileContent("abc123", expectedContent, activity) }
        assertEquals("Saved to Projects/2026/title.md", reattached.saveStatus.value)
    }
}
