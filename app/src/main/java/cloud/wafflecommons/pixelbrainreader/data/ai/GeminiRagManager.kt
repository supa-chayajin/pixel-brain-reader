package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import cloud.wafflecommons.pixelbrainreader.BuildConfig

@Singleton
class GeminiRagManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorSearchEngine: VectorSearchEngine
) {
    // Lazy initialization of the ML Kit client
    // Reverted to Simulation Mode due to unresolved API in alpha artifact
    
    suspend fun retrieveContext(query: String, limit: Int = 3): List<String> {
        return try {
            // Uses your existing VectorSearchEngine for Cosine Similarity
            val results = vectorSearchEngine.search(query, limit)
            results.map { it.content }
        } catch (e: Exception) {
            Log.e("Cortex", "RAG Retrieval Error", e)
            emptyList()
        }
    }

    fun buildAugmentedPrompt(userQuery: String, contextChunks: List<String>): String {
        if (contextChunks.isEmpty()) return userQuery
        val contextString = contextChunks.joinToString("\n\n---\n\n")
        return "CONTEXT:\n$contextString\n\nQUESTION:\n$userQuery"
    }

    /**
     * Generates content using Simulation Mode (Cloud Flash Lite) because ML Kit artifact API is unresolved.
     */
    suspend fun generateWithLocalEngine(prompt: String): String {
        Log.w("Cortex", "⚠️ Gemini Nano not available (API Unresolved). Simulating with Cloud Flash Lite.")
        
        return try {
             val generativeModel = GenerativeModel(
                modelName = "gemini-2.5-flash-lite",
                apiKey = BuildConfig.geminiApiKey
            )
            val response = generativeModel.generateContent(prompt)
            response.text ?: "No response generated."
        } catch (e: Exception) {
            Log.e("Cortex", "Simulation Error", e)
             "Simulation Error: ${e.message}"
        }
    }
    
    // --- Compatibility Methods (Legacy) ---
    
    suspend fun generateResponse(userMessage: String, useRAG: Boolean = true): Flow<String> = flow {
        val context = if (useRAG) retrieveContext(userMessage) else emptyList()
        val prompt = buildAugmentedPrompt(userMessage, context)
        try {
            val response = generateWithLocalEngine(prompt)
            emit(response)
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
    
    suspend fun analyzeFolder(files: List<Pair<String, String>>): String {
        return try {
             val fileContexts = files.joinToString("\n---\n") { (name, content) ->
                "File: $name\nContent:\n${content.take(2000)}"
            }
            val prompt = "Analyze these files and summarize their common themes and key points:\n$fileContexts"
            generateWithLocalEngine(prompt)
        } catch (e: Exception) {
            "Analysis Failed: ${e.message}"
        }
    }

    suspend fun findSources(query: String): List<String> {
        return retrieveContext(query)
    }
}

