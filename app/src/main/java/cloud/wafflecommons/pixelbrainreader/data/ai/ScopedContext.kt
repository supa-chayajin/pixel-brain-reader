package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One note read for a question (K2, 12.20): its text whole when it fits the prompt, else chained
 * summaries of its parts (the owner's D6). A note too long even for that is left to the caller,
 * which answers from its passages closest to the question. Every prompt is French
 * ([ScopedReading]).
 */
@Singleton
class ScopedContext @Inject constructor(
    private val noteRepository: NoteRepository,
    private val localAiManager: LocalAiManager
) {
    /** What the answer reads (null: nothing in the note helps, or it couldn't be read) and its source. */
    data class Reading(val context: String?, val sources: List<String>)

    /**
     * Null when the note has more than [ScopedReading.MAX_PARTS] parts. [onProgress] gets a short
     * line for the thinking bubble as the parts are read. A summary call that fails stops the
     * reading: the answer then fails the same way, with its own message.
     */
    suspend fun readNote(scope: ChatScope, question: String, onProgress: (String) -> Unit): Reading? {
        val content = noteRepository.getNoteContent(scope.path) ?: return Reading(null, emptyList())
        val body = ScopedReading.body(content)
        if (body.length <= ScopedReading.WHOLE_NOTE_CHARS) {
            return Reading(ScopedReading.wholeContext(scope.label, body), listOf(scope.path))
        }
        val parts = ScopedReading.parts(body)
        if (parts.size > ScopedReading.MAX_PARTS) return null
        val summaries = mutableListOf<String>()
        for ((index, part) in parts.withIndex()) {
            onProgress("Reading ${scope.label}: part ${index + 1} of ${parts.size}…")
            val prompt = ScopedReading.partPrompt(scope.label, index + 1, parts.size, part, question)
            summaries += localAiManager.generateResponse(prompt).getOrElse { return Reading(null, listOf(scope.path)) }
        }
        return Reading(ScopedReading.summariesContext(scope.label, summaries), listOf(scope.path))
    }
}
