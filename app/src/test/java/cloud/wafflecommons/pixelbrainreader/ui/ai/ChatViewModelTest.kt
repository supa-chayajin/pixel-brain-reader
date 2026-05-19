package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * In-memory fake mirroring [ChatRepository] semantics: per-mode storage with a
 * Flow surface, so the ViewModel's chatHistory StateFlow can observe writes.
 */
private class FakeChatRepository {
    private val flows = mutableMapOf<String, MutableStateFlow<List<ChatMessageEntity>>>()
    private fun flowFor(mode: String) = flows.getOrPut(mode) { MutableStateFlow(emptyList()) }

    val real: ChatRepository = mockk(relaxed = true)

    init {
        every { real.streamMessages(any()) } answers { flowFor(firstArg()) }
        coEvery { real.addMessage(any()) } answers {
            val msg = firstArg<ChatMessageEntity>()
            val flow = flowFor(msg.mode)
            flow.value = flow.value + msg
        }
        coEvery { real.recentForPrompt(any(), any()) } answers {
            val mode = firstArg<String>()
            val limit = secondArg<Int>()
            flowFor(mode).value.takeLast(limit)
        }
        coEvery { real.clear(any()) } answers {
            val mode = firstArg<String>()
            flowFor(mode).value = emptyList()
        }
    }

    fun snapshot(mode: String): List<ChatMessageEntity> = flowFor(mode).value
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ragManager: GeminiRagManager = mockk(relaxed = true)
    private val scribeManager: GeminiScribeManager = mockk(relaxed = true)
    private val localAiManager: LocalAiManager = mockk(relaxed = true)
    private val vectorSearchEngine: VectorSearchEngine = mockk(relaxed = true)
    private val nanoStateFlow = MutableStateFlow<NanoState>(NanoState.Ready)
    private lateinit var fakeRepo: FakeChatRepository

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        every { localAiManager.nanoState } returns nanoStateFlow
        // RAG default: empty hits unless a test overrides.
        coEvery { vectorSearchEngine.search(any(), any()) } returns emptyList()
        fakeRepo = FakeChatRepository()
        viewModel = ChatViewModel(
            ragManager = ragManager,
            scribeManager = scribeManager,
            localAiManager = localAiManager,
            chatRepository = fakeRepo.real,
            vectorSearchEngine = vectorSearchEngine
        )
    }

    @Test
    fun `initial state is correct`() {
        assertTrue(viewModel.chatHistory.value.isEmpty())
        assertNull(viewModel.loadingStage)
        assertFalse(viewModel.showCloudFallbackDialog)
        assertNull(viewModel.cloudFallbackReason)
        assertEquals(ScribePersona.TECH_WRITER, viewModel.currentPersona)
    }

    @Test
    fun `sendMessage uses local AI on success and never calls cloud`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), "Hello")
        } returns Result.success("On-device reply")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // Default mode is ORACLE -> stored as "RAG"
        val persisted = fakeRepo.snapshot("RAG")
        assertEquals(2, persisted.size)
        assertEquals("Hello", persisted[0].content)
        assertEquals("USER", persisted[0].role)
        assertEquals("On-device reply", persisted[1].content)
        assertEquals("MODEL", persisted[1].role)

        // Dialog never raised
        assertFalse(viewModel.showCloudFallbackDialog)

        // CRITICAL PRIVACY ASSERTION — cloud managers must not be invoked
        coVerify(exactly = 0) { scribeManager.generateScribeContent(any(), any()) }
        coVerify(exactly = 0) { ragManager.generateResponse(any(), any()) }
        coVerify(exactly = 0) { ragManager.findSources(any()) }
    }

    @Test
    fun `sendMessage on local failure raises dialog and does not call cloud`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), "Long prompt")
        } returns Result.failure(NanoException.ContextExceeded("token limit"))

        viewModel.sendMessage("Long prompt")
        advanceUntilIdle()

        // Only the user message — no bot bubble until consent
        val persisted = fakeRepo.snapshot("RAG")
        assertEquals(1, persisted.size)
        assertEquals("USER", persisted[0].role)

        // Dialog raised with a human-readable reason
        assertTrue(viewModel.showCloudFallbackDialog)
        assertTrue(
            "Reason should mention prompt length",
            viewModel.cloudFallbackReason?.contains("too long", ignoreCase = true) == true
        )

        // CRITICAL — cloud must not be touched
        coVerify(exactly = 0) { scribeManager.generateScribeContent(any(), any()) }
        coVerify(exactly = 0) { ragManager.generateResponse(any(), any()) }
        coVerify(exactly = 0) { ragManager.findSources(any()) }
    }

    @Test
    fun `onDismissCloudFallback clears state and never calls cloud`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), any())
        } returns Result.failure(NanoException.Unavailable("not present"))

        viewModel.sendMessage("anything")
        advanceUntilIdle()
        assertTrue(viewModel.showCloudFallbackDialog)

        viewModel.onDismissCloudFallback()
        advanceUntilIdle()

        assertFalse(viewModel.showCloudFallbackDialog)
        assertNull(viewModel.cloudFallbackReason)

        // No cloud calls ever
        coVerify(exactly = 0) { scribeManager.generateScribeContent(any(), any()) }
        coVerify(exactly = 0) { ragManager.generateResponse(any(), any()) }
        coVerify(exactly = 0) { ragManager.findSources(any()) }
    }

    @Test
    fun `onConfirmCloudFallback calls cloud once with pending prompt in SCRIBE mode`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), "write me a poem")
        } returns Result.failure(NanoException.Unavailable("not present"))
        coEvery { scribeManager.generateScribeContent("write me a poem", any()) } returns
            flowOf("roses ", "are ", "red")

        // Switch to SCRIBE so the cloud path uses scribeManager
        viewModel.toggleMode() // ORACLE -> SCRIBE
        assertEquals(ChatMode.SCRIBE, viewModel.currentMode.value)

        viewModel.sendMessage("write me a poem")
        advanceUntilIdle()
        assertTrue(viewModel.showCloudFallbackDialog)

        viewModel.onConfirmCloudFallback()
        advanceUntilIdle()

        assertFalse(viewModel.showCloudFallbackDialog)
        assertNull(viewModel.cloudFallbackReason)

        // Cloud was called exactly once, with the original prompt
        coVerify(exactly = 1) { scribeManager.generateScribeContent("write me a poem", any()) }

        // User msg + cloud bot msg, both persisted under "CREATIVE"
        val persisted = fakeRepo.snapshot("CREATIVE")
        assertEquals(2, persisted.size)
        assertEquals("USER", persisted[0].role)
        assertEquals("write me a poem", persisted[0].content)
        assertEquals("MODEL", persisted[1].role)
        assertEquals("roses are red", persisted[1].content)
        // Streaming overlay cleared on completion
        assertNull(viewModel.streamingMessage.value)
    }

    @Test
    fun `onConfirmCloudFallback uses RAG manager in ORACLE mode`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), "find my notes")
        } returns Result.failure(NanoException.Unavailable("not present"))
        coEvery { ragManager.findSources("find my notes") } returns listOf("vault/a.md")
        coEvery { ragManager.generateResponse("find my notes", useRAG = true) } returns
            flowOf("answer")

        // Default mode is ORACLE
        assertEquals(ChatMode.ORACLE, viewModel.currentMode.value)

        viewModel.sendMessage("find my notes")
        advanceUntilIdle()
        assertTrue(viewModel.showCloudFallbackDialog)

        viewModel.onConfirmCloudFallback()
        advanceUntilIdle()

        coVerify(exactly = 1) { ragManager.generateResponse("find my notes", useRAG = true) }
        coVerify(exactly = 1) { ragManager.findSources("find my notes") }
        coVerify(exactly = 0) { scribeManager.generateScribeContent(any(), any()) }
    }

    @Test
    fun `toggleMode swaps to the per-mode history bucket`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), any())
        } returns Result.success("reply")

        // ORACLE turn -> persisted under "RAG"
        viewModel.sendMessage("oracle q")
        advanceUntilIdle()

        // Switch to SCRIBE -> persisted under "CREATIVE"
        viewModel.toggleMode()
        viewModel.sendMessage("scribe q")
        advanceUntilIdle()

        assertEquals(2, fakeRepo.snapshot("RAG").size)
        assertEquals(2, fakeRepo.snapshot("CREATIVE").size)
        // Buckets are independent
        assertTrue(fakeRepo.snapshot("RAG").none { it.content == "scribe q" })
        assertTrue(fakeRepo.snapshot("CREATIVE").none { it.content == "oracle q" })
    }

    @Test
    fun `resetChat clears only the active mode's history`() = runTest {
        coEvery {
            localAiManager.generateAugmentedResponse(any(), any(), any(), any())
        } returns Result.success("reply")

        viewModel.sendMessage("oracle q")
        advanceUntilIdle()
        viewModel.toggleMode()
        viewModel.sendMessage("scribe q")
        advanceUntilIdle()

        // Clear SCRIBE while it's active
        viewModel.resetChat()
        advanceUntilIdle()

        assertEquals(0, fakeRepo.snapshot("CREATIVE").size)
        assertEquals(2, fakeRepo.snapshot("RAG").size)
    }

    @Test
    fun `switchPersona updates currentPersona`() {
        viewModel.switchPersona(ScribePersona.CODER)
        assertEquals(ScribePersona.CODER, viewModel.currentPersona)
    }

    @Test
    fun `nanoState is exposed from LocalAiManager`() {
        // Initially Ready (set in @Before)
        assertEquals(NanoState.Ready, viewModel.nanoState.value)

        nanoStateFlow.value = NanoState.Unavailable("no AICore")
        assertEquals(NanoState.Unavailable("no AICore"), viewModel.nanoState.value)
    }
}
