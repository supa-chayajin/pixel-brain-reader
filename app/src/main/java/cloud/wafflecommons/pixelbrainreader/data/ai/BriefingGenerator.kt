package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData

/**
 * The Daily board's short AI texts (quote, weather tip, briefing). A model failure comes back as a
 * [Result] from [LocalAiManager.generateResponse] and becomes the fallback text — it is never
 * detected by matching an error sentence (which also matched legitimate answers).
 */
@Singleton
class BriefingGenerator @Inject constructor(
    private val localAiManager: LocalAiManager
) {

    private val FALLBACK_QUOTE = "La seule façon de faire du bon travail, c'est d'aimer ce que l'on fait."

    suspend fun getDailyQuote(moodTrend: String): String {
        val prompt = "Génère une citation quotidienne inspirante ou stoïcienne à partir d'une tendance d'humeur : $moodTrend. Format de sortie : « Citation » - Auteur. ${AiLanguage.DIRECTIVE}"
        return localAiManager.generateResponse(prompt)
            .map { it.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_QUOTE
    }

    suspend fun getWeatherInsight(weather: WeatherData): String {
        val condition = "${weather.emoji} ${weather.description}"
        val prompt = "La météo actuelle est : $condition, ${weather.temperature}. Donne un seul conseil court et pratique (une seule phrase) pour la journée (par ex. « Prends un parapluie »). ${AiLanguage.DIRECTIVE}"
        Log.d("Cortex", "Generating weather insight for: ${weather.description}")
        return localAiManager.generateResponse(prompt)
            .onFailure { Log.w("Cortex", "Weather insight unavailable: ${it.message}") }
            .map { it.replace("\"", "").trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Prépare-toi pour la journée."
    }

    suspend fun generateBriefing(weather: WeatherData?): String {
        val weatherContext = if (weather != null) {
            "La météo d'aujourd'hui est ${weather.temperature}, ${weather.description}."
        } else {
            "Les données météo sont actuellement indisponibles."
        }
        val prompt = """
            $weatherContext
            Intègre cela dans un briefing quotidien concis.
            Reste sous les 50 mots.
            N'énonce pas explicitement « La météo est... », intègre-la naturellement dans un conseil ou une salutation.
            ${AiLanguage.DIRECTIVE}
        """.trimIndent()
        Log.d("WeatherAI", "Briefing generation triggered with weather context.")
        return localAiManager.generateResponse(prompt)
            .onFailure { Log.w("WeatherAI", "Briefing unavailable: ${it.message}") }
            .map { it.replace("\"", "").trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Prépare-toi pour une belle journée."
    }
}
