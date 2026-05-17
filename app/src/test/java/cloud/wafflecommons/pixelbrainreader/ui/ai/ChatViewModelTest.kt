package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
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

class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ragManager: GeminiRagManager = mockk(relaxed = true)
    private val scribeManager: GeminiScribeManager = mockk(relaxed = true)
    private val localAiManager: LocalAiManager = mockk(relaxed = true)
    private val nanoStateFlow = MutableStateFlow<NanoState>(NanoState.Ready)

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        every { localAiManager.nanoState } returns nanoStateFlow
        viewModel = ChatViewModel(ragManager, scribeManager, localAiManager)
    }

    @Test
    fun `initial state is correct`() {
        assertTrue(viewModel.messages.isEmpty())
        assertNull(viewModel.loadingStage)
        assertFalse(viewModel.showCloudFallbackDialog)
        assertNull(viewModel.cloudFallbackReason)
        assertEquals(ScribePersona.TECH_WRITER, viewModel.currentPersona)
    }

    @Test
    fun `sendMessage uses local AI on success and never calls cloud`() = runTest {
        coEvery { localAiManager.generateResponse("Hello") } returns Result.success("On-device reply")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // User message + local bot reply
        assertEquals(2, viewModel.messages.size)
        assertEquals("Hello", viewModel.messages[0].content)
        assertTrue(viewModel.messages[0].isUser)
        assertEquals("On-device reply", viewModel.messages[1].content)
        assertFalse(viewModel.messages[1].isUser)
        assertFalse(viewModel.messages[1].isStreaming)

        // Dialog never raised
        assertFalse(viewModel.showCloudFallbackDialog)

        // CRITICAL PRIVACY ASSERTION — cloud managers must not be invoked
        coVerify(exactly = 0) { scribeManager.generateScribeContent(any(), any()) }
        coVerify(exactly = 0) { ragManager.generateResponse(any(), any()) }
        coVerify(exactly = 0) { ragManager.findSources(any()) }
    }

    @Test
    fun `sendMessage on local failure raises dialog and does not call cloud`() = runTest {
        coEvery { localAiManager.generateResponse("Long prompt") } returns Result.failure(
            NanoException.ContextExceeded("token limit")
        )

        viewModel.sendMessage("Long prompt")
        advanceUntilIdle()

        // Only the user message — no bot bubble until consent
        assertEquals(1, viewModel.messages.size)

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
        coEvery { localAiManager.generateResponse(any()) } returns Result.failure(
            NanoException.Unavailable("not present")
        )

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
        coEvery { localAiManager.generateResponse("write me a poem") } returns Result.failure(
            NanoException.Unavailable("not present")
        )
        coEvery { scribeManager.generateScribeContent("write me a poem", any()) } returns
            flowOf("roses ", "are ", "red")

        // Switch to SCRIBE so the cloud path uses scribeManager
        viewModel.toggleMode() // ORACLE -> SCRIBE
        assertEquals(ChatMode.SCRIBE, viewModel.currentMode)

        viewModel.sendMessage("write me a poem")
        advanceUntilIdle()
        assertTrue(viewModel.showCloudFallbackDialog)

        viewModel.onConfirmCloudFallback()
        advanceUntilIdle()

        assertFalse(viewModel.showCloudFallbackDialog)
        assertNull(viewModel.cloudFallbackReason)

        // Cloud was called exactly once, with the original prompt
        coVerify(exactly = 1) { scribeManager.generateScribeContent("write me a poem", any()) }

        // User msg + cloud bot msg
        assertEquals(2, viewModel.messages.size)
        val botMessage = viewModel.messages.last()
        assertEquals("roses are red", botMessage.content)
        assertFalse(botMessage.isUser)
        assertFalse(botMessage.isStreaming)
    }

    @Test
    fun `onConfirmCloudFallback uses RAG manager in ORACLE mode`() = runTest {
        coEvery { localAiManager.generateResponse("find my notes") } returns Result.failure(
            NanoException.Unavailable("not present")
        )
        coEvery { ragManager.findSources("find my notes") } returns listOf("vault/a.md")
        coEvery { ragManager.generateResponse("find my notes", useRAG = true) } returns
            flowOf("answer")

        // Default mode is ORACLE
        assertEquals(ChatMode.ORACLE, viewModel.currentMode)

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
