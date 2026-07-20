package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import cloud.wafflecommons.pixelbrainreader.data.model.RpgCharacter
import cloud.wafflecommons.pixelbrainreader.data.model.LifeStatsLogic
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData

@Singleton
class BriefingGenerator @Inject constructor(
    private val geminiRagManager: GeminiRagManager
) {
    
    private val FALLBACK_QUOTE = "La seule façon de faire du bon travail, c'est d'aimer ce que l'on fait."

    suspend fun getDailyQuote(moodTrend: String): String {
        return try {
            val prompt = "Génère une citation quotidienne inspirante ou stoïcienne à partir d'une tendance d'humeur : $moodTrend. Format de sortie : « Citation » - Auteur. ${AiLanguage.DIRECTIVE}"
            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
             var result = ""
             flow.collect { if (!it.startsWith("Thinking")) result = it }
            
            if (result.isBlank()) FALLBACK_QUOTE else result
        } catch (e: Exception) {
            FALLBACK_QUOTE
        }
    }
    
    suspend fun getWeatherInsight(weather: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData): String {
        val tag = "Cortex"
        return try {
            val condition = "${weather.emoji} ${weather.description}"
            val prompt = "La météo actuelle est : $condition, ${weather.temperature}. Donne un seul conseil court et pratique (une seule phrase) pour la journée (par ex. « Prends un parapluie »). ${AiLanguage.DIRECTIVE}"
            
            Log.d(tag, "Generating weather insight for: ${weather.description}")

            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
             var result = ""
             flow.collect { response ->
                 if (!response.startsWith("Thinking")) {
                      result = response
                 }
             }
            
            if (result.isBlank()) {
                "Prépare-toi pour la journée."
            } else {
                result.replace("\"", "").trim()
            }
        } catch (e: Exception) {
             Log.e(tag, "Weather AI Failed", e)
             "Prépare-toi pour la journée. (IA indisponible)"
        }
    }

    suspend fun generateBriefing(weather: WeatherData?): String {
        val tag = "WeatherAI"
        return try {
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

            Log.d(tag, "Briefing generation triggered with weather context.")

            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
            var result = ""
            flow.collect { response ->
                 if (!response.startsWith("Thinking")) {
                      result = response
                 }
            }
            
            if (result.isBlank()) {
                "Prépare-toi pour une belle journée."
            } else {
                result.replace("\"", "").trim()
            }
        } catch (e: Exception) {
             Log.e(tag, "Briefing Gen Failed", e)
             "Get ready for a great day."
        }
    }
}

