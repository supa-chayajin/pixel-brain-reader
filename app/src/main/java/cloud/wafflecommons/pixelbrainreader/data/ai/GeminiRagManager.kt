package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * On-device RAG facade. Retrieves grounding context from the local
 * [VectorSearchEngine] and runs generation entirely through [LocalAiManager]
 * (Gemini Nano). There is NO cloud path — the app is 100% on-device.
 */
@Singleton
class GeminiRagManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorSearchEngine: VectorSearchEngine,
    private val localAiManager: LocalAiManager
) {

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

    /**
     * On-device RAG generation. Retrieves optional context, then runs the prompt
     * through [LocalAiManager]. Kept as a `Flow<String>` (a "Thinking…" placeholder
     * followed by the final answer) for backwards-compatibility with the non-chat
     * consumers that collect it and skip the placeholder (daily briefing/quote,
     * Oracle insight, ImportWorker summary, folder insight via [analyzeFolder]).
     */
    suspend fun generateResponse(userMessage: String, useRAG: Boolean = false): Flow<String> = flow {
        emit("Thinking...")

        var prompt = userMessage
        if (useRAG) {
            val context = retrieveContext(userMessage)
            if (context.isNotEmpty()) {
                prompt = buildAugmentedPrompt(userMessage, context)
            }
        }

        emit(generateWithLocalEngine(prompt))
    }

    /**
     * Executes the prompt on Gemini Nano (On-Device) via [LocalAiManager], which
     * owns the model lifecycle and fast-fails when the model is not Ready.
     */
    suspend fun generateWithLocalEngine(prompt: String): String {
        Log.d("Cortex", "Prompting Gemini Nano via LocalAiManager…")
        return localAiManager.generateResponse(prompt).fold(
            onSuccess = { it },
            onFailure = { e ->
                Log.e("Cortex", "Local AI generation failed", e)
                "Cortex (Local) unavailable: ${e.localizedMessage ?: e.message ?: "unknown error"}"
            }
        )
    }

    suspend fun analyzeFolder(files: List<Pair<String, String>>): String {
        return try {
             // Summarize approach to fit context window
             val fileContexts = files.take(10).joinToString("\n---\n") { (name, content) ->
                "File: $name\nContent:\n${content.take(1500)}"
            }
            val prompt = "Analyze these files and summarize their common themes, key points, and any interesting connections:\n$fileContexts"

            val flow = generateResponse(prompt, useRAG = false) // No RAG for folder analysis, context provided in prompt

            // Collect flow, skipping the "Thinking..." placeholder.
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
