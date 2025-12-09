package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiScribeManager @Inject constructor() {

    // Using the API Key from BuildConfig as requested
    private val apiKey = BuildConfig.geminiApiKey

    /**
     * Generates text content based on the user's prompt and the selected persona.
     * Returns a Flow that emits the text chunks as they are generated.
     */
    fun generateScribeContent(prompt: String, persona: ScribePersona): Flow<String> {
        val systemInstruction = when (persona) {
            ScribePersona.TECH_WRITER -> "You are an expert Technical Writer. Produce clear, concise, and structured documentation. Use markdown headers and bullet points."
            ScribePersona.CODER -> "You are a Senior Software Engineer. Generate clean, efficient, and well-commented code. Explain the logic briefly."
            ScribePersona.PLANNER -> "You are a Product Manager. Create structured plans, user stories, and roadmaps. Focus on business value and timelines."
        }

        val generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            systemInstruction = content { text(systemInstruction) }
        )

        return generativeModel.generateContentStream(prompt).map { chunk ->
            chunk.text ?: ""
        }
    }
}
