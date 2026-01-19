package cloud.wafflecommons.pixelbrainreader.data.model

enum class AiModel(val id: String, val displayName: String) {
    GEMINI_FLASH("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
    GEMINI_PRO("gemini-2.5-pro", "Gemini 2.5 Pro"),
    CORTEX_LOCAL("cortex-local", "Cortex (On-Device)");

    companion object {
        fun fromId(id: String): AiModel = entries.find { it.id == id } ?: GEMINI_FLASH
    }
}
