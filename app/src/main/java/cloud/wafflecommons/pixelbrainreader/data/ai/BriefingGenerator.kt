package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BriefingGenerator @Inject constructor(
    private val geminiRagManager: GeminiRagManager
) {
    
    private val FALLBACK_QUOTE = "The only way to do great work is to love what you do."

    suspend fun getDailyQuote(moodTrend: String): String {
        return try {
            val prompt = "Generate an inspiring or stoic daily quote in French based on a mood trend of $moodTrend. Output Format: 'Quote' - Author."
            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
            // Wait for full response (last emitted value that isn't 'Thinking')
             var result = ""
             flow.collect { response ->
                 if (!response.startsWith("Thinking")) {
                      result = response
                 }
             }
            
            if (result.isBlank()) FALLBACK_QUOTE else result
        } catch (e: Exception) {
            FALLBACK_QUOTE
        }
    }
    
    suspend fun getWeatherInsight(weather: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData): String {
        val tag = "Cortex"
        return try {
            // "Forecast" is generic, so we prepend emoji which carries semantic meaning for the AI
            val condition = "${weather.emoji} ${weather.description}"
            val prompt = "Voici la météo actuelle : $condition, ${weather.temperature}. Donne un conseil court et pratique (une seule phrase) en français pour la journée (ex: 'Prends un parapluie')."
            
            Log.d(tag, "Generating weather insight for: ${weather.description}")
            Log.d(tag, "Prompt: $prompt")

            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
             var result = ""
             flow.collect { response ->
                 if (!response.startsWith("Thinking")) {
                      result = response
                 }
             }
            
            if (result.isBlank()) {
                Log.w(tag, "AI returned empty response for weather insight")
                "Préparez-vous pour la journée." // Soft fallback
            } else {
                Log.d(tag, "AI Result: $result")
                result.replace("\"", "").trim()
            }
        } catch (e: Exception) {
             Log.e(tag, "Weather AI Failed", e)
             "Préparez-vous pour la journée. (IA Indisponible)"
        }
    }
    
}

