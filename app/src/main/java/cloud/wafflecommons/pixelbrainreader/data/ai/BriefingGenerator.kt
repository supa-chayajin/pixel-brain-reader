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
    
    private val FALLBACK_QUOTE = "The only way to do great work is to love what you do."

    suspend fun getDailyQuote(moodTrend: String): String {
        return try {
            val prompt = "Generate an inspiring or stoic daily quote in English based on a mood trend of $moodTrend. Output Format: 'Quote' - Author."
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
            val prompt = "The current weather is: $condition, ${weather.temperature}. Give one short, practical piece of advice (a single sentence) in English for the day (e.g. 'Take an umbrella')."
            
            Log.d(tag, "Generating weather insight for: ${weather.description}")

            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            
             var result = ""
             flow.collect { response ->
                 if (!response.startsWith("Thinking")) {
                      result = response
                 }
             }
            
            if (result.isBlank()) {
                "Get ready for the day."
            } else {
                result.replace("\"", "").trim()
            }
        } catch (e: Exception) {
             Log.e(tag, "Weather AI Failed", e)
             "Get ready for the day. (AI Unavailable)"
        }
    }

    suspend fun generateBriefing(weather: WeatherData?): String {
        val tag = "WeatherAI"
        return try {
            val weatherContext = if (weather != null) {
                "Today's weather is ${weather.temperature}, ${weather.description}."
            } else {
                "Weather data is currently unavailable."
            }

            val prompt = """
                $weatherContext
                Incorporate this into a concise daily briefing in English.
                Keep it under 50 words.
                Do not explicitly state 'The weather is...', weave it naturally into advice or a greeting.
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
                "Get ready for a great day."
            } else {
                result.replace("\"", "").trim()
            }
        } catch (e: Exception) {
             Log.e(tag, "Briefing Gen Failed", e)
             "Get ready for a great day."
        }
    }
}

