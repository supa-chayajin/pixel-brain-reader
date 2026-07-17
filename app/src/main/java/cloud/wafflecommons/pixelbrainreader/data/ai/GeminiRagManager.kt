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
     * as note content.
     */
    suspend fun analyzeFolder(
        files: List<Pair<String, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<String> {
        val usable = files.filter { it.second.isNotBlank() }.take(MAX_ANALYZE_FILES)
        if (usable.isEmpty()) {
            return Result.failure(IllegalStateException("No usable files to analyze."))
        }

        // Re-probe on-device model readiness before firing one inference per file — a stale
        // cold-start availability probe would otherwise doom every call silently.
        localAiManager.refreshAvailability()

        // MAP: one concise summary per file. Each prompt stays well under Nano's context.
        // A single file failing (Nano legitimately returns empty responses fairly often) must
        // NOT abort the whole folder — only a genuinely unavailable model aborts early.
        val perFile = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var modelUnavailable: Throwable? = null
        for ((index, pair) in usable.withIndex()) {
            val (name, content) = pair
            onProgress(index, usable.size)
            val prompt = buildString {
                appendLine("Summarize this document in 1 to 2 concise sentences, in English.")
                appendLine("Title: $name")
                appendLine("Content:")
                append(content.take(PER_FILE_CHARS))
            }
            localAiManager.generateResponse(prompt).fold(
                onSuccess = { summary ->
                    val s = summary.trim()
                    if (s.isNotBlank()) perFile.add("- **$name**: $s") else skipped.add(name)
                },
                onFailure = { e ->
                    if (e is NanoException.Unavailable) {
                        modelUnavailable = e
                    } else {
                        Log.e("Cortex", "Folder analysis: per-file summary failed for $name", e)
                        skipped.add(name)
                    }
                }
            )
            if (modelUnavailable != null) break
        }
        onProgress(usable.size, usable.size)

        // Model not ready, or every file failed → surface an actionable failure (no note written).
        if (perFile.isEmpty()) {
            return Result.failure(
                modelUnavailable
                    ?: IllegalStateException("The on-device model returned no summaries. Please try again.")
            )
        }

        val joined = perFile.joinToString("\n")
        val skippedNote = if (skipped.isNotEmpty()) "\n\n> ⚠️ ${skipped.size} file(s) could not be summarized." else ""

        // REDUCE: synthesize the folder from the (small) per-file summaries. If this step
        // fails, the per-file summaries are still a useful, complete result on their own.
        val overviewPrompt = buildString {
            appendLine("Here are the summaries of the notes in a folder:")
            appendLine(joined)
            appendLine()
            appendLine("Write a short markdown synthesis of the folder: common themes, key points and interesting links between the notes.")
        }
        val overview = localAiManager.generateResponse(overviewPrompt).getOrNull()

        val body = if (overview.isNullOrBlank()) {
            "## Folder synthesis\n\n$joined$skippedNote"
        } else {
            "${overview.trim()}\n\n---\n\n### Per-file summaries\n$joined$skippedNote"
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
