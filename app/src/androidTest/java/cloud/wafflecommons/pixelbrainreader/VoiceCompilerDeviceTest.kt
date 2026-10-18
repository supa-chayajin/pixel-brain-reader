package cloud.wafflecommons.pixelbrainreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.VoiceCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs VoiceCompiler's patterns on ANDROID's regex engine (ICU). The JVM unit tests can't: 10.2.0
 * shipped a `(?U)` pattern that the JVM accepts and ICU rejects, crashing the app at start while
 * every unit test passed. Run on the emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCompilerDeviceTest {

    private val compiler = VoiceCompiler(
        LocalAiManager(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    )

    @Test
    fun offlineCompilerSplitsFrenchActionsOnIcu() {
        val result = compiler.fallbackCompile("Paul et Marie sont passés. Penser à acheter du pain, appeler maman")

        assertEquals("- Paul et Marie sont passés", result.structuredMarkdown)
        assertEquals(listOf("Acheter du pain", "Appeler maman"), result.detectedTasks)
    }

    @Test
    fun modelAnswerParsingRunsOnIcu() {
        val parsed = compiler.parseAiOutput(
            "brut",
            "**TÂCHES:**\n- [ ] Réparer le vélo #maison\nNOTE:\n- Une idée"
        )

        assertEquals(listOf("Réparer le vélo"), parsed!!.detectedTasks)
        assertTrue(parsed.structuredMarkdown.contains("Une idée"))
    }
}
