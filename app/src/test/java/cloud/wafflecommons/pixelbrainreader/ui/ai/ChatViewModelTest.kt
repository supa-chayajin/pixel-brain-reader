package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
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
            localAiManager = localAiManager,
            chatRepository = fakeRepo.real,
            vectorSearchEngine = vectorSearchEngine
        )
    }

    @Test
    fun `initial state is correct`() {
        assertTrue(viewModel.chatHistory.value.isEmpty())
        assertNull(viewModel.loadingStage)
        assertEquals(ChatMode.ORACLE, viewModel.currentMode.value)
    }

    @Test
    fun `sendMessage persists user and model turns on success`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), "Hello")
        } returns flowOf("On-device reply")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // Default mode is ORACLE -> stored as "RAG"
        val persisted = fakeRepo.snapshot("RAG")
        assertEquals(2, persisted.size)
        assertEquals("Hello", persisted[0].content)
        assertEquals("USER", persisted[0].role)
        assertEquals("On-device reply", persisted[1].content)
        assertEquals("MODEL", persisted[1].role)
    }

    @Test
    fun `sendMessage persists an inline error turn on local failure`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), "Long prompt")
        } returns flow { throw NanoException.ContextExceeded("token limit") }

        viewModel.sendMessage("Long prompt")
        advanceUntilIdle()

        // No cloud fallback: user turn + an inline error MODEL turn.
        val persisted = fakeRepo.snapshot("RAG")
        assertEquals(2, persisted.size)
        assertEquals("USER", persisted[0].role)
        assertEquals("MODEL", persisted[1].role)
        assertTrue(
            "Error turn should carry the warning marker",
            persisted[1].content.startsWith("⚠️")
        )
    }

    @Test
    fun `toggleMode swaps to the per-mode history bucket`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), any())
        } returns flowOf("reply")

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
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), any())
        } returns flowOf("reply")

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
    fun `nanoState is exposed from LocalAiManager`() {
        // Initially Ready (set in @Before)
        assertEquals(NanoState.Ready, viewModel.nanoState.value)

        nanoStateFlow.value = NanoState.Unavailable("no AICore")
        assertEquals(NanoState.Unavailable("no AICore"), viewModel.nanoState.value)
    }
}
