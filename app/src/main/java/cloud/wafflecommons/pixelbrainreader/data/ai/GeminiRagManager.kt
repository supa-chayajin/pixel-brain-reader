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

    /**
     * Folder analysis via **map-reduce** summarization, entirely on-device.
     *
     * The old implementation concatenated up to 10 files (1500 chars each) into ONE prompt
     * — ~15k chars — which blew past Gemini Nano's input context window, so the call failed
     * with ContextExceeded and the error string was written into the note as if it were the
     * answer, and any answer that did come back was truncated by Nano's output cap.
     *
     * Now each file is summarized on its own (small, safe input), then the per-file summaries
     * (small combined input) are reduced into a folder synthesis. Returns [Result] so real
     * failures (model not ready / context / timeout) surface to the UI instead of masquerading
     * as note content. Prompts are in French to match the app's on-device assistant persona.
     */
    suspend fun analyzeFolder(
        files: List<Pair<String, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<String> {
        val usable = files.filter { it.second.isNotBlank() }.take(MAX_ANALYZE_FILES)
        if (usable.isEmpty()) {
            return Result.failure(IllegalStateException("Aucun fichier exploitable à analyser."))
        }

        // MAP: one concise summary per file. Each prompt stays well under Nano's context.
        val perFile = mutableListOf<String>()
        usable.forEachIndexed { index, (name, content) ->
            onProgress(index, usable.size)
            val prompt = buildString {
                appendLine("Résume ce document en 1 à 2 phrases concises, en français.")
                appendLine("Titre : $name")
                appendLine("Contenu :")
                append(content.take(PER_FILE_CHARS))
            }
            val summary = localAiManager.generateResponse(prompt).getOrElse { e ->
                Log.e("Cortex", "Folder analysis: per-file summary failed for $name", e)
                return Result.failure(e)
            }
            perFile.add("- **$name** : ${summary.trim()}")
        }
        onProgress(usable.size, usable.size)

        val joined = perFile.joinToString("\n")

        // REDUCE: synthesize the folder from the (small) per-file summaries. If this step
        // fails, the per-file summaries are still a useful, complete result on their own.
        val overviewPrompt = buildString {
            appendLine("Voici les résumés des notes d'un même dossier :")
            appendLine(joined)
            appendLine()
            appendLine("Rédige une courte synthèse markdown du dossier : thèmes communs, points clés et liens intéressants entre les notes.")
        }
        val overview = localAiManager.generateResponse(overviewPrompt).getOrNull()

        val body = if (overview.isNullOrBlank()) {
            "## Synthèse du dossier\n\n$joined"
        } else {
            "$overview\n\n---\n\n### Résumés par fichier\n$joined"
        }
        return Result.success(body)
    }

    suspend fun findSources(query: String): List<String> {
        return retrieveContext(query)
    }

    private companion object {
        /** Max files summarized in one folder analysis (map step runs one Nano call each). */
        const val MAX_ANALYZE_FILES = 15
        /** Per-file input cap — small enough that a single summary never exceeds Nano's context. */
        const val PER_FILE_CHARS = 2000
    }
}
