package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A time-travel answer (K4, 12.20; the owner's D9): the notes about the question, each as it stood
 * then and as it is now, the prompt that compares them, and the mark the answer keeps in its
 * sources so each source chip opens its then-vs-now diff. French prompts (settled decision #5).
 * Pure.
 */
object ThenVsNow {

    /** Each side of a note is cut to this, so three notes fit Nano's window. */
    const val PER_SIDE_CHARS = 1_200

    /** At most this many notes are compared. */
    const val NOTES = 3

    private const val MARK = "then:"
    private val FRENCH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

    /** One note: its text then (null: not written yet) and now (null: deleted since). */
    data class Note(val path: String, val then: String?, val now: String?)

    fun frenchDay(day: LocalDate): String = FRENCH_DAY.format(day)

    /** What the answer reads: each note then and now; null when none of them existed either way. */
    fun context(notes: List<Note>, then: LocalDate): String? {
        val useful = notes.filter { it.then != null || it.now != null }
        if (useful.isEmpty()) return null
        val day = frenchDay(then)
        return useful.joinToString("\n\n") { note ->
            val title = note.path.substringAfterLast('/').removeSuffix(".md")
            "[Note : $title — telle qu'elle était le $day]\n" + side(note.then, "(pas encore écrite à cette date)") +
                "\n\n[Note : $title — aujourd'hui]\n" + side(note.now, "(supprimée depuis)")
        }
    }

    private fun side(text: String?, missing: String): String =
        text?.let { ScopedReading.body(it).take(PER_SIDE_CHARS).ifBlank { "(vide)" } } ?: missing

    /** The question as Nano gets it: the comparison asked, then a three-line summary of the shift. */
    fun ask(question: String, then: LocalDate): String =
        "$question\n\nCompare ce que ces notes disaient le ${frenchDay(then)} et ce qu'elles disent aujourd'hui, " +
            "puis résume en trois lignes ce qui a changé dans ma façon de voir les choses. " +
            "Ne cite que ce que les notes contiennent.\n\n" + AiLanguage.DIRECTIVE

    /** The mark a time-travel answer keeps in its sources, with the day it looked back to. */
    fun mark(then: LocalDate): String = MARK + then

    /** The day an answer looked back to, or null for an ordinary answer. */
    fun dayOf(sources: List<String>): LocalDate? =
        sources.firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The notes among [sources], the mark left out (chips, Save to Inbox). */
    fun notesOf(sources: List<String>): List<String> = sources.filterNot { it.startsWith(MARK) }
}

/** A note as it stood at the end of a day, from its git history (K4), and as it is now. */
@Singleton
class NoteTimeTravel @Inject constructor(
    private val jGitProvider: JGitProvider,
    private val noteRepository: NoteRepository
) {
    /**
     * The newest version on or before [day], under the name it had then (K3 follows renames); null
     * when the note didn't exist yet, or its history doesn't reach back that far.
     */
    suspend fun asOf(path: String, day: LocalDate): String? {
        val endOfDay = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val version = jGitProvider.getFileCommitHistory(path, limit = HISTORY_LIMIT)
            .firstOrNull { it.timestamp < endOfDay } ?: return null
        return jGitProvider.getFileContentAtCommit(version.path ?: path, version.commitId)
    }

    suspend fun now(path: String): String? = noteRepository.getNoteContent(path)

    private companion object {
        /** Versions read to find the one of that day: enough for a few years of a busy note. */
        const val HISTORY_LIMIT = 500
    }
}
