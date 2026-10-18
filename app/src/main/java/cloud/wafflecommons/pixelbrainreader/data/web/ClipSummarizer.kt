package cloud.wafflecommons.pixelbrainreader.data.web

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import javax.inject.Inject
import javax.inject.Singleton

/** The on-device summary of an imported article (Gemini Nano, ~4K-token window: the input is capped). */
@Singleton
class ClipSummarizer @Inject constructor(private val localAiManager: LocalAiManager) {

    /** Three bullet points, or null for a short article or when the model can't answer (never a sentence about the failure). */
    suspend fun summarize(markdown: String): String? {
        if (markdown.length < MIN_CHARS) return null
        val prompt = "Résume cet article en 3 puces concises :\n\n${markdown.take(INPUT_CHARS)}\n\n${AiLanguage.DIRECTIVE}"
        return localAiManager.generateResponse(prompt).getOrNull()?.trim()?.ifEmpty { null }
    }

    companion object {
        /** Below this, an article is its own summary. */
        const val MIN_CHARS = 500
        private const val INPUT_CHARS = 4_000
    }
}
