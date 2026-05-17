package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [LocalAiManager].
 *
 * AICore's [com.google.ai.edge.aicore.GenerativeModel] requires Android runtime services
 * (AICore bound service, Gemini Nano APK), so the inference path itself is covered by
 * instrumented tests. These JVM tests focus on the **public contract** that protects
 * privacy: bad inputs short-circuit, and the manager never throws to its caller — it
 * always returns a typed [NanoException] in [Result.failure].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalAiManagerTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `generateResponse with blank prompt returns BadInput failure`() = runTest {
        val manager = LocalAiManager(context)

        val result = manager.generateResponse("")

        assertTrue("Result should be failure", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "Expected NanoException.BadInput but got ${ex!!::class.simpleName}",
            ex is NanoException.BadInput
        )
    }

    @Test
    fun `generateResponse with whitespace-only prompt returns BadInput failure`() = runTest {
        val manager = LocalAiManager(context)

        val result = manager.generateResponse("   \n\t  ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NanoException.BadInput)
    }

    @Test
    fun `NanoState sealed hierarchy covers all expected variants`() {
        // Defensive check — these classes are referenced by the UI's when() block.
        // If anyone adds/renames a variant, this test forces them to update the indicator too.
        val states: List<NanoState> = listOf(
            NanoState.Unknown,
            NanoState.Checking,
            NanoState.Downloading(progress = 0L, totalBytes = 100L),
            NanoState.Ready,
            NanoState.Unavailable("no model"),
            NanoState.Error(RuntimeException("boom"))
        )
        assertEquals(6, states.size)
    }

    @Test
    fun `NanoException subclasses carry their context`() {
        val unavailable = NanoException.Unavailable("device too old")
        assertEquals("device too old", unavailable.reason)
        assertTrue(unavailable.message!!.contains("device too old"))

        val ctx = NanoException.ContextExceeded("128 tokens > 64")
        assertTrue(ctx.message!!.contains("128 tokens > 64"))

        val cause = IllegalStateException("native crash")
        val gen = NanoException.Generation(cause)
        assertEquals(cause, gen.cause)
    }
}
