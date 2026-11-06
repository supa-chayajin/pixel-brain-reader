package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answering the live TODO's open questions from the phone (C13): the answer becomes one nested
 * line under the question, `  - 💬 yyyy-MM-dd — answer`, which the next desktop session reads
 * with the TODO. Not a checkbox: an answer is never an action. The question line itself is left
 * as written; the session ticks it once it acted on the answer.
 */
object TodoAnswers {

    private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

    /** A line of Claude's queue that asks you something: under a ❓ heading, typed, or a question. */
    fun isQuestion(action: ProjectAction): Boolean =
        action.section?.contains("❓") == true ||
            action.text.startsWith("question:", ignoreCase = true) ||
            action.text.trimEnd().endsWith("?")

    /**
     * [content] with [answer] under [questionLine], after the answers and sub-items already nested
     * there; null when that exact line is no longer in the file (it changed meanwhile).
     */
    fun answer(content: String, questionLine: String, answer: String, today: LocalDate): String? {
        val lines = content.split("\n").toMutableList()
        val index = lines.indexOf(questionLine)
        if (index < 0) return null
        val nested = questionLine.takeWhile { it == ' ' || it == '\t' } + "  "
        var at = index + 1
        while (at < lines.size && lines[at].isNotBlank() && lines[at].startsWith(nested)) at++
        lines.add(at, "$nested- 💬 $today — ${answer.replace(LINE_BREAKS, " ").trim()}")
        val updated = lines.joinToString("\n")
        return if (FrontmatterLines.hasFrontmatter(updated)) FrontmatterLines.set(updated, "date_updated", "'$today'") else updated
    }
}

/** Writes answers into a project's live TODO, reading it from disk first (settled decision #8). */
@Singleton
class ProjectTodoRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val mutex = Mutex()

    /** False when the question's line changed on disk meanwhile, or the TODO became a mirror (nothing written). */
    suspend fun answer(question: ProjectAction, answer: String, today: LocalDate = LocalDate.now()): Boolean {
        val line = question.sourceLine ?: return false
        if (answer.isBlank()) return false
        val written = withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = noteRepository.getNoteContent(question.path) ?: return@withLock false
                if (MirrorNotes.isMirror(current)) return@withLock false
                val updated = TodoAnswers.answer(current, line, answer, today) ?: return@withLock false
                noteRepository.saveNote(question.path, updated)
                true
            }
        }
        if (written) pushScheduler.requestPush()
        return written
    }
}
