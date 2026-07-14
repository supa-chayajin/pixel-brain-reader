package cloud.wafflecommons.pixelbrainreader.data.model

/**
 * On-device AI model selection. The app is 100% on-device — the only engine is
 * Gemini Nano via ML Kit GenAI. (Cloud Gemini Flash/Pro were removed.)
 */
enum class AiModel(val id: String, val displayName: String) {
    CORTEX_LOCAL("cortex-local", "Cortex (On-Device)");

    companion object {
        fun fromId(id: String): AiModel = entries.find { it.id == id } ?: CORTEX_LOCAL
    }
}
