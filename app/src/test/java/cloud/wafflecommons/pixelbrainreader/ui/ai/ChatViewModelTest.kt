package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeed
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatScope
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatScopeRepository
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeedRepository
import cloud.wafflecommons.pixelbrainreader.data.ai.NoteTimeTravel
import cloud.wafflecommons.pixelbrainreader.data.ai.ScopedContext
import cloud.wafflecommons.pixelbrainreader.data.ai.ThenVsNow
import cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.PromptPresetFormat
import cloud.wafflecommons.pixelbrainreader.data.ai.PromptPresetRepository
import cloud.wafflecommons.pixelbrainreader.data.ai.NoteRetrieval
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val noteRetrieval: NoteRetrieval = mockk(relaxed = true)
    private val presetRepository: PromptPresetRepository = mockk {
        every { presets } returns MutableStateFlow(PromptPresetFormat.DEFAULTS)
        coEvery { reload() } just runs
    }
    private val nanoStateFlow = MutableStateFlow<NanoState>(NanoState.Ready)
    private val chatSeedRepository = ChatSeedRepository()
    private val chatScopeRepository = ChatScopeRepository()
    private val scopedContext: ScopedContext = mockk()
    private val noteTimeTravel: NoteTimeTravel = mockk()
    private lateinit var fakeRepo: FakeChatRepository

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        every { localAiManager.nanoState } returns nanoStateFlow
        // RAG default: empty hits unless a test overrides.
        coEvery { noteRetrieval.search(any(), any()) } returns emptyList()
        fakeRepo = FakeChatRepository()
        viewModel = ChatViewModel(
            localAiManager = localAiManager,
            chatRepository = fakeRepo.real,
            noteRetrieval = noteRetrieval,
            presetRepository = presetRepository,
            chatSeedRepository = chatSeedRepository,
            chatScopeRepository = chatScopeRepository,
            scopedContext = scopedContext,
            noteTimeTravel = noteTimeTravel
        )
    }

    @Test
    fun `a question about the past compares each note then and now, and keeps the day`() = runTest {
        val day = java.time.LocalDate.now().minusYears(1)
        coEvery { noteRetrieval.search(match { !it.contains("il y a") }, any()) } returns listOf(
            SearchHit("Garden/Plan.md", "passage", isPrivate = false)
        )
        coEvery { noteTimeTravel.asOf("Garden/Plan.md", day) } returns "Tomates en avril."
        coEvery { noteTimeTravel.now("Garden/Plan.md") } returns "Tomates en mai."
        every {
            localAiManager.generateAugmentedResponseStream(
                any(),
                match { it?.contains("Tomates en avril.") == true && it.contains("Tomates en mai.") },
                any(),
                match { it.contains("Compare ce que ces notes disaient") }
            )
        } returns flowOf("Avril, puis mai.")

        viewModel.sendMessage("Que pensais-je du jardin il y a un an ?")
        advanceUntilIdle()

        val answer = fakeRepo.snapshot("RAG")[1]
        assertEquals("Avril, puis mai.", answer.content)
        assertEquals(day, ThenVsNow.dayOf(answer.sources))
        assertEquals(listOf("Garden/Plan.md"), ThenVsNow.notesOf(answer.sources))
    }

    // --- K2 (12.20): Ask this note / this folder, and Continue ---

    @Test
    fun `an offered folder opens Cortex on it, and the question keeps its scope`() = runTest {
        val folder = ChatScope.folder("20_Projects/21_Garden")!!
        coEvery { noteRetrieval.search("when?", any(), folder) } returns listOf(
            SearchHit("20_Projects/21_Garden/Plan.md", "[Note: Plan] Tomatoes in April", isPrivate = false)
        )
        every {
            localAiManager.generateAugmentedResponseStream(any(), match { it?.contains("Tomatoes") == true }, any(), "when?")
        } returns flowOf("En avril.")

        viewModel.setMode(ChatMode.SCRIBE)
        chatScopeRepository.offer(folder)
        advanceUntilIdle()
        assertEquals(ChatMode.ORACLE, viewModel.currentMode.value)
        assertEquals(folder, viewModel.scope.value)

        viewModel.sendMessage("when?")
        advanceUntilIdle()

        val persisted = fakeRepo.snapshot("RAG")
        assertEquals(folder, ChatScope.decode(persisted[0].sources))
        assertEquals("En avril.", persisted[1].content)
        assertEquals(listOf("20_Projects/21_Garden/Plan.md"), persisted[1].sources)
        viewModel.clearScope()
        assertNull(viewModel.scope.value)
    }

    @Test
    fun `a note is read through its own text, without searching the vault`() = runTest {
        val note = ChatScope.note("Ideas.md")!!
        coEvery { scopedContext.readNote(note, "what?", any()) } returns ScopedContext.Reading("[Note : Ideas]\nA garden.", listOf("Ideas.md"))
        every {
            localAiManager.generateAugmentedResponseStream(any(), "[Note : Ideas]\nA garden.", any(), "what?")
        } returns flowOf("Un jardin.")

        chatScopeRepository.offer(note)
        advanceUntilIdle()
        viewModel.sendMessage("what?")
        advanceUntilIdle()

        coVerify(exactly = 0) { noteRetrieval.search(any(), any(), any()) }
        assertEquals(listOf("Ideas.md"), fakeRepo.snapshot("RAG")[1].sources)
    }

    @Test
    fun `a note too long for chained summaries is answered from its closest passages`() = runTest {
        val note = ChatScope.note("Ideas.md")!!
        coEvery { scopedContext.readNote(note, "what?", any()) } returns null
        coEvery { noteRetrieval.search("what?", any(), note) } returns listOf(SearchHit("Ideas.md", "passage", isPrivate = false))
        every { localAiManager.generateAugmentedResponseStream(any(), "passage", any(), "what?") } returns flowOf("Voilà.")

        chatScopeRepository.offer(note)
        advanceUntilIdle()
        viewModel.sendMessage("what?")
        advanceUntilIdle()

        coVerify(exactly = 1) { noteRetrieval.search("what?", any(), note) }
        assertEquals("Voilà.", fakeRepo.snapshot("RAG")[1].content)
    }

    @Test
    fun `an answer stopped at the cap offers Continue, which reads the same context without searching`() = runTest {
        every { localAiManager.lastStreamStoppedAtCap } returns true
        coEvery { noteRetrieval.search("q", any()) } returns listOf(SearchHit("a.md", "ctx", isPrivate = false))
        every { localAiManager.generateAugmentedResponseStream(any(), any(), any(), any()) } returns flowOf("a long answer")

        viewModel.sendMessage("q")
        advanceUntilIdle()
        val answer = fakeRepo.snapshot("RAG")[1]
        assertEquals(answer.id, viewModel.continuableAnswerId)

        every { localAiManager.lastStreamStoppedAtCap } returns false
        viewModel.continueAnswer()
        advanceUntilIdle()

        coVerify(exactly = 1) { noteRetrieval.search(any(), any()) }
        verify { localAiManager.generateAugmentedResponseStream(any(), "ctx", any(), match { it.startsWith("Continue ta réponse") }) }
        val persisted = fakeRepo.snapshot("RAG")
        assertEquals("Continue", persisted[2].content)
        assertEquals(listOf("a.md"), persisted[3].sources)
        assertNull(viewModel.continuableAnswerId)
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
    fun `a seed from a work screen switches to its preset and is sent once`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), "Mon objectif « Lead »")
        } returns flowOf("Une question ouverte ?")

        chatSeedRepository.offer(ChatSeed("coach", "Mon objectif « Lead »"))
        advanceUntilIdle()

        assertEquals(ChatMode.PRESET, viewModel.currentMode.value)
        assertEquals("coach", viewModel.activePreset.value.id)
        val persisted = fakeRepo.snapshot("PRESET:coach")
        assertEquals(listOf("USER", "MODEL"), persisted.map { it.role })
        assertEquals("Mon objectif « Lead »", persisted[0].content)
        // Taken once: nothing left for another chat screen to replay.
        assertNull(chatSeedRepository.take())
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
        viewModel.setMode(ChatMode.SCRIBE)
        viewModel.sendMessage("scribe q")
        advanceUntilIdle()

        assertEquals(2, fakeRepo.snapshot("RAG").size)
        assertEquals(2, fakeRepo.snapshot("CREATIVE").size)
        // Buckets are independent
        assertTrue(fakeRepo.snapshot("RAG").none { it.content == "scribe q" })
        assertTrue(fakeRepo.snapshot("CREATIVE").none { it.content == "oracle q" })
    }

    @Test
    fun `toggleMode cycles through ORACLE, PRESET, and SCRIBE`() {
        assertEquals(ChatMode.ORACLE, viewModel.currentMode.value)
        viewModel.toggleMode()
        assertEquals(ChatMode.PRESET, viewModel.currentMode.value)
        viewModel.toggleMode()
        assertEquals(ChatMode.SCRIBE, viewModel.currentMode.value)
        viewModel.toggleMode()
        assertEquals(ChatMode.ORACLE, viewModel.currentMode.value)
    }

    @Test
    fun `the Boardroom preset searches the notes and keeps its BOARDROOM history`() = runTest {
        coEvery { noteRetrieval.search("arch query", any()) } returns listOf(
            cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit(
                fileId = "20_Projects/21_Waffle_Framework/Architecture.md",
                content = "Clean Architecture Core Rules",
                isPrivate = false
            )
        )
        every {
            localAiManager.generateAugmentedResponseStream(
                systemPrompt = match { it.contains("Alexandre") && it.contains("Bastien") && it.contains("Camille") },
                ragContext = match { it?.contains("Clean Architecture Core Rules") == true },
                chatHistory = any(),
                currentQuery = "arch query"
            )
        } returns flowOf("### 🏛️ Alexandre\nDDD Analysis...")

        viewModel.setMode(ChatMode.PRESET)
        viewModel.sendMessage("arch query")
        advanceUntilIdle()

        val persisted = fakeRepo.snapshot("BOARDROOM")
        assertEquals(2, persisted.size)
        assertEquals("arch query", persisted[0].content)
        assertEquals("### 🏛️ Alexandre\nDDD Analysis...", persisted[1].content)
        assertEquals(listOf("20_Projects/21_Waffle_Framework/Architecture.md"), persisted[1].sources)
    }

    @Test
    fun `a preset without notes skips the search, ends with the French directive and keeps its own history`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(
                systemPrompt = match { it.contains("coach de poche") && it.endsWith(cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage.SYSTEM_DIRECTIVE) },
                ragContext = null,
                chatHistory = any(),
                currentQuery = "a hard 1:1"
            )
        } returns flowOf("Reformulation…")

        viewModel.selectPreset("coach")
        viewModel.sendMessage("a hard 1:1")
        advanceUntilIdle()

        coVerify(exactly = 0) { noteRetrieval.search(any(), any()) }
        assertEquals(listOf("a hard 1:1", "Reformulation…"), fakeRepo.snapshot("PRESET:coach").map { it.content })
        assertTrue(fakeRepo.snapshot("BOARDROOM").isEmpty())
    }

    @Test
    fun `resetChat clears only the active mode's history`() = runTest {
        every {
            localAiManager.generateAugmentedResponseStream(any(), any(), any(), any())
        } returns flowOf("reply")

        viewModel.sendMessage("oracle q")
        advanceUntilIdle()
        viewModel.setMode(ChatMode.SCRIBE)
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
