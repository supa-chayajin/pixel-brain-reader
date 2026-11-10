package cloud.wafflecommons.pixelbrainreader.data.ai

import java.util.Locale

/**
 * One self-test of Gemini Nano: a real request through the production request builder, then one
 * streamed answer, each timed. "Ready" only says ML Kit has the model — in 11.1.0 it showed Ready
 * while every request failed on an option the pre-load never sent.
 */
data class NanoSelfTest(
    /** When it ran (epoch millis). */
    val at: Long,
    val oneShot: Step,
    /** Null when the test could not start (the model is not ready): only [oneShot] says why. */
    val stream: Step?
) {
    /** One timed call. A null [error] means it answered. */
    data class Step(
        val millis: Long,
        /** Time to the first streamed words; null for the one-shot call. */
        val firstChunkMillis: Long? = null,
        /** "stop", "max tokens", "other", or null when ML Kit gave none. */
        val finishReason: String? = null,
        val chars: Int = 0,
        val error: String? = null
    )

    val passed: Boolean get() = oneShot.error == null && stream != null && stream.error == null

    /** The first error, for a one-line banner; null when the test passed. */
    val firstError: String? get() = oneShot.error ?: stream?.error

    /** One line for Settings, measured values only. */
    fun summary(): String {
        val streamed = stream
        if (!passed || streamed == null) return "Failed: ${firstError ?: "no answer"}"
        return buildString {
            append("Answered in ").append(seconds(oneShot.millis))
            oneShot.finishReason?.let { append(" (").append(it).append(')') }
            append(" · streamed in ").append(seconds(streamed.millis))
            streamed.firstChunkMillis?.let { append(", first words after ").append(seconds(it)) }
        }
    }

    companion object {
        /** French, like every prompt (settled decision #5), and short: the test costs two answers. */
        internal const val PROMPT =
            "Test de fonctionnement de l'assistant sur l'appareil. Réponds en une seule phrase courte : « Tout fonctionne. »\n\n" +
                AiLanguage.DIRECTIVE

        internal fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
    }
}
