package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class GeminiRagManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorSearchEngine: VectorSearchEngine
) {
    // Initialize the ML Kit Generative Model
    // Based on Google Samples: defaults to Gemini Nano
    private val localModel: GenerativeModel? = initOrGetModel()

    private fun initOrGetModel(): GenerativeModel? {
        return null// TODO: URGENT !!!!
    }

    suspend fun retrieveContext(query: String, limit: Int = 3): List<String> {
        return try {
            // Hybrid Search logic (Cosine Similarity)
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
        
        return """
        CONTEXT (My Notes):
        $contextString
        
        QUESTION:
        $userQuery
        
        INSTRUCTIONS:
        Answer based on the Context above. Be concise.
        """.trimIndent()
    }

    /**
     * Executes the prompt on Gemini Nano (On-Device).
     * Uses ML Kit Prompt API.
     */
    suspend fun generateWithLocalEngine(prompt: String): String {
        Log.d("Cortex", "🚀 Prompting Gemini Nano via ML Kit...")
        
        return try {
            val text = localModel?.generateContent(prompt).toString()
            text.ifBlank {
                "Thinking..." // or generic fallback
            }
        } catch (e: Exception) {
            Log.e("Cortex", "Gemini Nano Inference Failed", e)
            throw Exception("Local Brain Error: ${e.localizedMessage}. Ensure Gemini Nano is downloaded.")
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

