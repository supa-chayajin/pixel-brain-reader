package cloud.wafflecommons.pixelbrainreader.data.ai

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceCompilerTest {

    private lateinit var geminiRagManager: GeminiRagManager
    private lateinit var voiceCompiler: VoiceCompiler

    @Before
    fun setUp() {
        geminiRagManager = mockk()
        voiceCompiler = VoiceCompiler(geminiRagManager)
    }

    @Test
    fun compile_emptyTranscript_returnsEmpty() = runBlocking {
        val result = voiceCompiler.compile("")
        assertEquals("", result.rawTranscript)
        assertTrue(result.detectedTasks.isEmpty())
    }

    @Test
    fun compile_validAiOutput_parsesTasksCategoryAndContent() = runBlocking {
        val transcript = "Réunion architecture Waffle demain, penser à fixer le token GitHub, et acheter du café"
        val mockAiResponse = """
            CATEGORY: 20_Projects
            TASKS:
            - [ ] Fixer le token GitHub #waffle #security
            - [ ] Acheter du café #chores
            CONTENT:
            - Réunion architecture Waffle planifiée demain.
        """.trimIndent()

        coEvery { geminiRagManager.generateResponse(any(), useRAG = false) } returns flowOf(mockAiResponse)

        val result = voiceCompiler.compile(transcript)

        assertEquals("20_Projects", result.suggestedCategory)
        assertEquals(2, result.detectedTasks.size)
        assertEquals("Fixer le token GitHub #waffle #security", result.detectedTasks[0])
        assertEquals("Acheter du café #chores", result.detectedTasks[1])
        assertTrue(result.structuredMarkdown.contains("Réunion architecture Waffle"))
    }

    @Test
    fun fallbackCompile_extractsActionsAndHeuristicCategory() {
        val transcript = "Penser à appeler Camille pour le projet Waffle et acheter du thé"

        val result = voiceCompiler.fallbackCompile(transcript)

        assertEquals("20_Projects", result.suggestedCategory)
        assertTrue(result.detectedTasks.isNotEmpty())
        assertTrue(result.detectedTasks.any { it.contains("Appeler Camille", ignoreCase = true) })
        assertTrue(result.detectedTasks.any { it.contains("Acheter du thé", ignoreCase = true) })
    }
}
