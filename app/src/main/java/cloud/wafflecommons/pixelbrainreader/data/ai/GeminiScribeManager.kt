package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiScribeManager @Inject constructor(
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) {

    // Using the API Key from BuildConfig as requested
    private val apiKey = BuildConfig.geminiApiKey

    /**
     * Generates text content based on the user's prompt and the selected persona.
     * Returns a Flow that emits the text chunks as they are generated.
     */
    fun generateScribeContent(prompt: String, persona: ScribePersona): Flow<String> = kotlinx.coroutines.flow.flow {
        val userLanguage = java.util.Locale.getDefault().displayLanguage
        
        val strictRule = "STRICT OUTPUT RULE: Do not add conversational filler. Provide the requested code, documentation, or text immediately. If creating a file, start with the file block syntax."
        
        val systemInstruction = when (persona) {
            ScribePersona.TECH_WRITER -> "You are an expert Technical Writer. Produce clear, concise, and structured documentation. Use markdown headers and bullet points. ALWAYS answer in $userLanguage language. $strictRule"
            ScribePersona.CODER -> "You are a Senior Software Engineer. Generate clean, efficient, and well-commented code. Explain the logic briefly. ALWAYS answer in $userLanguage language. $strictRule"
            ScribePersona.PLANNER -> "You are a Product Manager. Create structured plans, user stories, and roadmaps. Focus on business value and timelines. ALWAYS answer in $userLanguage language. $strictRule"
        }
        
        // Dynamic Model Selection
        // Dynamic Model Selection
        val modelName = userPrefs.selectedAiModel.first().id

        val generativeModel = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            systemInstruction = content { text(systemInstruction) }
        )

        val stream = generativeModel.generateContentStream(prompt).map { chunk ->
            chunk.text ?: ""
        }
        
        emitAll(stream)
    }
}
