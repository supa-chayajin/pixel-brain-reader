package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
            NanoState.NotDownloaded,
            NanoState.Downloading(progress = 0.5f, bytesDownloaded = 50L, totalBytes = 100L),
            NanoState.Ready,
            NanoState.Unavailable("no model"),
            NanoState.Error(RuntimeException("boom"))
        )
        assertEquals(7, states.size)
    }

    @Test
    fun `generateResponse fast-fails with Unavailable when state is not Ready`() = runTest {
        // The manager starts in NanoState.Unknown. With a mocked Context there is no
        // path to NanoState.Ready, so generateResponse must NOT trigger a download
        // or warmup — it must return Result.failure(NanoException.Unavailable) without
        // ever touching the model. This locks in the lifecycle-decoupling contract.
        val manager = LocalAiManager(context)

        val result = manager.generateResponse("hello")

        assertTrue("Result should be failure", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "Expected NanoException.Unavailable but got ${ex!!::class.simpleName}",
            ex is NanoException.Unavailable
        )
    }

    /**
     * Settled decision #1 (CLAUDE.md): a DOWNLOADABLE model is fetched automatically — once per
     * process — so the AI surfaces work without a Settings visit. A later probe must not loop.
     */
    @Test
    fun `DOWNLOADABLE model auto-starts the download exactly once`() = runBlocking {
        val model = mockk<GenerativeModel>(relaxed = true)
        coEvery { model.checkStatus() } returns FeatureStatus.DOWNLOADABLE
        every { model.download() } returns flow { throw IllegalStateException("offline") }
        mockkObject(Generation)
        try {
            every { Generation.getClient() } returns model
            val manager = LocalAiManager(context)

            verify(timeout = 5_000, exactly = 1) { model.download() }
            // Let the failed download settle (it leaves the Downloading state).
            withTimeout(5_000) { manager.nanoState.first { it is NanoState.Error } }

            manager.refreshAvailability()

            assertEquals(NanoState.NotDownloaded, manager.nanoState.value)
            verify(exactly = 1) { model.download() }
        } finally {
            unmockkObject(Generation)
        }
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
