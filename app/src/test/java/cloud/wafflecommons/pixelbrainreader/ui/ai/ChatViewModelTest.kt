package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private lateinit var viewModel: ChatViewModel
    // Dependencies
    private val ragManager: GeminiRagManager = mockk(relaxed = true)
    private val scribeManager: GeminiScribeManager = mockk(relaxed = true)

    @Before
    fun setup() {
        viewModel = ChatViewModel(ragManager, scribeManager)
    }

    @Test
    fun `initial state is correct`() {
        assertTrue(viewModel.messages.isEmpty())
        assertFalse(viewModel.isLoading)
        assertEquals(ScribePersona.TECH_WRITER, viewModel.currentPersona)
    }

    @Test
    fun `switchPersona updates currentPersona`() {
        viewModel.switchPersona(ScribePersona.CODER)
        assertEquals(ScribePersona.CODER, viewModel.currentPersona)
    }

    @Test
    fun `sendMessage success flow`() = runTest {
        // Given
        val prompt = "Hello"
        val responseChunk1 = "Hello"
        val responseChunk2 = " World"
        
        // Mock the flow response
        coEvery { scribeManager.generateScribeContent(prompt, any()) } returns flowOf(responseChunk1, responseChunk2)

        // When
        viewModel.sendMessage(prompt)

        // 1. Assert Immediate State (Optimistic Update)
        assertEquals(2, viewModel.messages.size)
        
        // User Message
        val userMsg = viewModel.messages[0]
        assertEquals(prompt, userMsg.content)
        assertTrue(userMsg.isUser)
        
        // Bot Placeholder
        val botMsg = viewModel.messages[1]
        assertEquals("Thinking...", botMsg.content)
        assertFalse(botMsg.isUser)
        assertTrue("Message should be streaming initially", botMsg.isStreaming)
        assertTrue("ViewModel should be loading", viewModel.isLoading)

        // 2. Advance Time to let flow collect
        advanceUntilIdle()

        // 3. Assert Final State
        assertFalse("ViewModel should stop loading", viewModel.isLoading)
        
        val finalBotMsg = viewModel.messages[1]
        assertEquals("Hello World", finalBotMsg.content)
        assertFalse("Streaming should be done", finalBotMsg.isStreaming)
        
        verify { scribeManager.generateScribeContent(prompt, any()) }
    }

    @Test
    fun `sendMessage error handling`() = runTest {
        // Given
        val prompt = "Error Prompt"
        val errorMessage = "Network Failure"
        coEvery { scribeManager.generateScribeContent(prompt, any()) } returns flow {
            throw Exception(errorMessage)
        }

        // When
        viewModel.sendMessage(prompt)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.isLoading)
        val botMessage = viewModel.messages[1]
        
        // Verify error text is present
        assertTrue("Content should contain error prefix", botMessage.content.contains("Error"))
        assertTrue("Content should contain exception message", botMessage.content.contains(errorMessage))
        assertFalse("Streaming should stop on error", botMessage.isStreaming)
    }
}
