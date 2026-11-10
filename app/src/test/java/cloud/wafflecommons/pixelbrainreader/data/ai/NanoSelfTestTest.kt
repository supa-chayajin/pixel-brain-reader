package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The self-test's verdict and its one line: measured values only (T1, 12.9.0). */
class NanoSelfTestTest {

    private val ok = NanoSelfTest.Step(millis = 1_240, finishReason = "stop", chars = 17)
    private val streamed = NanoSelfTest.Step(millis = 2_050, firstChunkMillis = 610, finishReason = "stop", chars = 17)

    @Test
    fun `both calls answered - passed, with their times`() {
        val test = NanoSelfTest(at = 0, oneShot = ok, stream = streamed)
        assertTrue(test.passed)
        assertNull(test.firstError)
        assertEquals("Answered in 1.2 s (stop) · streamed in 2.1 s, first words after 0.6 s", test.summary())
    }

    @Test
    fun `a model that is not ready fails at once with the reason, no stream tried`() {
        val test = NanoSelfTest(at = 0, oneShot = NanoSelfTest.Step(millis = 0, error = "model is downloading (40%)"), stream = null)
        assertFalse(test.passed)
        assertEquals("Failed: model is downloading (40%)", test.summary())
    }

    @Test
    fun `a stream that fails after a good one-shot answer still fails the test`() {
        val test = NanoSelfTest(at = 0, oneShot = ok, stream = NanoSelfTest.Step(millis = 60_000, error = "an empty stream"))
        assertFalse(test.passed)
        assertEquals("an empty stream", test.firstError)
        assertEquals("Failed: an empty stream", test.summary())
    }

    @Test
    fun `the test prompt is French and ends with the language directive (settled decision #5)`() {
        assertTrue(NanoSelfTest.PROMPT.contains("Réponds"))
        assertTrue(NanoSelfTest.PROMPT.endsWith(AiLanguage.DIRECTIVE))
    }
}
