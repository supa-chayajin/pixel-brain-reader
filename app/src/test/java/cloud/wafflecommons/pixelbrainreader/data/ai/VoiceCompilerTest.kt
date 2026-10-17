package cloud.wafflecommons.pixelbrainreader.data.ai

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceCompilerTest {

    private lateinit var localAiManager: LocalAiManager
    private lateinit var voiceCompiler: VoiceCompiler

    @Before
    fun setUp() {
        localAiManager = mockk()
        voiceCompiler = VoiceCompiler(localAiManager)
    }

    @Test
    fun `an empty transcript returns an empty result without calling the model`() = runBlocking {
        val result = voiceCompiler.compile("   ")
        assertEquals("", result.rawTranscript)
        assertTrue(result.detectedTasks.isEmpty())
        coVerify(exactly = 0) { localAiManager.generateResponse(any()) }
    }

    @Test
    fun `a well-formed model answer yields tasks without hashtags and the note bullets`() = runBlocking {
        coEvery { localAiManager.generateResponse(any()) } returns Result.success(
            """
            **TÂCHES:**
            - [ ] Fixer le token GitHub #waffle
            - [ ] Acheter du café
            NOTE:
            - Réunion architecture Waffle planifiée demain.
            """.trimIndent()
        )

        val result = voiceCompiler.compile("Réunion Waffle demain, penser à fixer le token et acheter du café")

        assertEquals(listOf("Fixer le token GitHub", "Acheter du café"), result.detectedTasks)
        assertEquals("- Réunion architecture Waffle planifiée demain.", result.structuredMarkdown)
    }

    @Test
    fun `an echoed format template is not turned into tasks or bullets`() = runBlocking {
        coEvery { localAiManager.generateResponse(any()) } returns Result.success(
            "TACHES:\n- [ ] action\nNOTE:\n- idée\n- Une vraie idée"
        )

        val result = voiceCompiler.compile("une vraie idée")

        assertTrue(result.detectedTasks.isEmpty())
        assertEquals("- Une vraie idée", result.structuredMarkdown)
    }

    @Test
    fun `when the model is unavailable the offline heuristic still captures the note`() = runBlocking {
        coEvery { localAiManager.generateResponse(any()) } returns
            Result.failure(NanoException.Unavailable("model not downloaded"))

        val result = voiceCompiler.compile("Penser à appeler Camille pour le projet Waffle et acheter du thé")

        assertTrue(result.detectedTasks.any { it.startsWith("Appeler Camille") })
        assertTrue(result.detectedTasks.contains("Acheter du thé"))
    }

    @Test
    fun `an answer without the TACHES or NOTE markers falls back instead of becoming the note`() = runBlocking {
        // e.g. the unavailable sentence, or the model chatting instead of following the format
        coEvery { localAiManager.generateResponse(any()) } returns Result.success("Bien sûr ! Voici un résumé…")

        val result = voiceCompiler.compile("Le serveur est indisponible depuis ce matin")

        // The transcript itself is kept — the word "indisponible" no longer means "AI failure".
        assertEquals("- Le serveur est indisponible depuis ce matin", result.structuredMarkdown)
        assertTrue(result.detectedTasks.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates instead of being swallowed`(): Unit = runBlocking {
        coEvery { localAiManager.generateResponse(any()) } throws CancellationException("screen closed")
        voiceCompiler.compile("acheter du pain")
    }

    @Test
    fun `fallback keeps a sentence without an action intact instead of splitting on et`() {
        val result = voiceCompiler.fallbackCompile("Paul et Marie sont passés. Acheter du pain, appeler maman")

        assertEquals("- Paul et Marie sont passés", result.structuredMarkdown)
        assertEquals(listOf("Acheter du pain", "Appeler maman"), result.detectedTasks)
    }

    @Test
    fun `summaryLine takes the first bullet and truncates it`() {
        val compiled = CompiledVoiceResult(
            rawTranscript = "raw",
            structuredMarkdown = "- " + "x".repeat(80) + "\n- second",
            detectedTasks = emptyList()
        )
        val line = VoiceCompiler.summaryLine(compiled, maxChars = 20)
        assertEquals(20, line.length)
        assertTrue(line.endsWith("…"))
    }
}
