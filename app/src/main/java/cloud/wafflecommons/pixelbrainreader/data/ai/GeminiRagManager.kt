package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class GeminiRagManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorSearchEngine: VectorSearchEngine,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) {
    // Initialize the ML Kit Generative Model (Local)
    private val localModel: GenerativeModel? by lazy { initOrGetModel() }

    private fun initOrGetModel(): GenerativeModel? {
        // FIXME: GenerativeModel is an interface. Need to find correct Factory/Builder.
        // Disabling Local AI for now to allow build.
        Log.e("Cortex", "ML Kit GenerativeModel init disabled: API mismatch.")
        return null
    }

    // --- RAG Core ---
    private suspend fun retrieveContext(query: String): List<String> {
        return vectorSearchEngine.search(query).map { it.content }
    }

    private fun buildAugmentedPrompt(userMessage: String, contextChunks: List<String>): String {
        return if (contextChunks.isEmpty()) {
            userMessage
        } else {
            """
            Context from my notes:
            ${contextChunks.joinToString("\n---\n")}
            
            Based on the context above, answer the user's question:
            $userMessage
            """.trimIndent()
        }
    }

    suspend fun generateResponse(userMessage: String, useRAG: Boolean = true): Flow<String> = flow {
        // 1. Initial State
        emit("Thinking...")
        
        // 2. Resolve Model Preference
        val modelPrefs = userPrefs.selectedAiModel.firstOrNull() ?: cloud.wafflecommons.pixelbrainreader.data.model.AiModel.GEMINI_FLASH
        
        // 3. RAG Retrieval
        var prompt = userMessage
        if (useRAG) {
            val context = retrieveContext(userMessage)
            if (context.isNotEmpty()) {
                prompt = buildAugmentedPrompt(userMessage, context)
            }
        }
        
        // 4. Inference Routing
        val response = if (modelPrefs == cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL) {
            generateWithLocalEngine(prompt)
        } else {
             // Cloud
             val apiKey = BuildConfig.geminiApiKey
             if (apiKey.isBlank()) {
                 emit("Error: Gemini API Key not found. Please check settings.")
                 return@flow
             }
             generateWithRemoteEngine(prompt, apiKey, modelPrefs.id)
        }
        
        emit(response)
    }

    /**
     * Executes the prompt on Gemini Nano (On-Device).
     * Uses ML Kit Prompt API.
     */
    suspend fun generateWithLocalEngine(prompt: String): String {
        Log.d("Cortex", "🚀 Prompting Gemini Nano via ML Kit...")
        val model = localModel
        if (model == null) return "Cortex Intelligence (Local) is not available on this device."
        
            val response = model.generateContent(prompt)
             return response.candidates.firstOrNull()?.text ?: "No response from Cortex."

    }

    /**
     * Executes the prompt on Gemini Cloud (Flash/Pro)
     * Uses Google AI Client SDK
     */
    suspend fun generateWithRemoteEngine(prompt: String, apiKey: String, modelId: String): String {
        Log.d("Cortex", "☁️ Prompting Cloud Model: $modelId")
        Log.d("WeatherInsight", "Prompt: $prompt")
        return try {
            val remoteModel = com.google.ai.client.generativeai.GenerativeModel(
                modelName = modelId,
                apiKey = apiKey
            )
            val response = remoteModel.generateContent(prompt)
            response.text ?: "No response received."
        } catch (e: Exception) {
             "Cloud Error: ${e.localizedMessage}"
        }
    }
    
    suspend fun analyzeFolder(files: List<Pair<String, String>>): String {
        return try {
             // Summarize approach to fit context window
             val fileContexts = files.take(10).joinToString("\n---\n") { (name, content) ->
                "File: $name\nContent:\n${content.take(1500)}"
            }
            val prompt = "Analyze these files and summarize their common themes, key points, and any interesting connections:\n$fileContexts"
            
            // Re-use routing logic via flow? Or direct call?
            // Since generateResponse emits a Flow and adds "Thinking...", let's just use it and take the last value.
            // But we need a String return.
            
            val flow = generateResponse(prompt, useRAG = false) // No RAG for folder analysis, context provided in prompt
            
            // Collect flow
            var result = ""
            flow.collect { 
                 if (!it.startsWith("Thinking")) result = it
            }
            result.ifBlank { "Analysis failed or timed out." }
            
        } catch (e: Exception) {
            "Analysis Failed: ${e.message}"
        }
    }

    suspend fun findSources(query: String): List<String> {
        return retrieveContext(query)
    }
}
