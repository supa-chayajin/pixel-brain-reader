package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import cloud.wafflecommons.pixelbrainreader.BuildConfig

@Singleton
class GeminiRagManager @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) {

    // --- Unified Entry Point ---

    /**
     * Generates a response stream based on the user's selected AI Model.
     * Handles switching between Cloud (Gemini) and Local (Gemini Nano) engines.
     *
     * @param userMessage The user's query.
     * @param useRAG Whether to use Retrieval-Augmented Generation (context from notes).
     */
    suspend fun generateResponse(userMessage: String, useRAG: Boolean = true): Flow<String> {
        val selectedModel = userPrefs.selectedAiModel.first()
        
        // 1. Context Retrieval (RAG)
        var finalPrompt = userMessage
        if (useRAG) {
            val relevantDocs = vectorSearchEngine.search(userMessage, limit = 3)
            if (relevantDocs.isNotEmpty()) {
                val contextBlock = relevantDocs.joinToString("\n\n") { it.content }
                finalPrompt = """
                    CONTEXT:
                    $contextBlock
                    
                    USER QUESTION: $userMessage
                    
                    INSTRUCTION: Answer using ONLY the context above. If the answer is not there, say you don't know.
                """.trimIndent()
            }
        }

        // 2. Model Switching & Execution
        return when (selectedModel) {
            cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository.AiModel.CORTEX_LOCAL -> {
                generateWithLocalEngine(finalPrompt)
            }
            else -> {
                generateWithCloudEngine(selectedModel.id, finalPrompt)
            }
        }
    }

    // --- Engines ---

    private fun generateWithCloudEngine(modelId: String, prompt: String): Flow<String> {
        val generativeModel = GenerativeModel(
            modelName = modelId,
            apiKey = BuildConfig.geminiApiKey
        )
        return generativeModel.generateContentStream(prompt).map { it.text ?: "" }
    }

    private fun generateWithLocalEngine(prompt: String): Flow<String> {
        // Placeholder for Gemini Nano (AICore) implementation.
        // Currently simulating using Flash Lite to prevent build errors as AICore dependency is missing.
        // TODO: Replace with Google AI Edge SDK implementation when available.
        android.util.Log.w("Cortex", "Gemini Nano not integrated. Simulating with Cloud Flash Lite.")
        
        return try {
             val generativeModel = GenerativeModel(
                modelName = "gemini-2.5-flash-lite", // Fallback simulation
                apiKey = BuildConfig.geminiApiKey
            )
            generativeModel.generateContentStream(prompt).map { it.text ?: "" }
        } catch (e: Exception) {
            kotlinx.coroutines.flow.flowOf("Cortex Error: Local Engine currently unavailable. (${e.localizedMessage})")
        }
    }

    // --- Legacy / Aux Methods ---

    suspend fun findSources(query: String): List<String> {
        return vectorSearchEngine.search(query).map { it.fileId }.distinct()
    }

    suspend fun analyzeFolder(files: List<Pair<String, String>>): String {
        if (files.isEmpty()) return "No files to analyze."
        
        val fileContexts = files.joinToString("\n---\n") { (name, content) ->
            "File: $name\nContent:\n${content.take(2000)}"
        }

        val userLanguage = java.util.Locale.getDefault().displayLanguage
        val prompt = "Analyze these files and summarize in $userLanguage:\n$fileContexts"
        
        // Use Cloud Model for heavy analysis
        return try {
            val model = GenerativeModel(
                modelName = "gemini-2.5-flash-lite",
                apiKey = BuildConfig.geminiApiKey
            )
            model.generateContent(prompt).text ?: "No content."
        } catch (e: Exception) {
            "Analysis Failed: ${e.message}"
        }
    }
}

