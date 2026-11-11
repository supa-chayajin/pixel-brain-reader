package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind

/** What a meeting's prep is built from (D4, owner's review 2026-11-11), pure. */
object MeetingPrep {

    /** Notes listed on the prep page. */
    const val MAX_NOTES = 6

    /** Decisions listed: the latest ones about the meeting. */
    const val MAX_DECISIONS = 3

    /** How far back the mission log is read for decisions. */
    const val DECISION_DAYS = 180L

    /**
     * The meeting's subject from its timeline line: the Google import writes "[Calendar] Title";
     * the calendar tag, the bracketed asides ("(Teams)", "[visio]") and edge dashes go.
     */
    fun topic(timelineText: String): String {
        var text = timelineText.trim()
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end > 0) text = text.substring(end + 1)
        }
        val kept = StringBuilder()
        var depth = 0
        for (c in text) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth-- else kept.append(c)
                else -> if (depth == 0) kept.append(c)
            }
        }
        return kept.toString().split(' ', '\t').filter { it.isNotBlank() }.joinToString(" ")
            .trim().trim('-', '—', '–', ':', '·', '|').trim()
    }

    /**
     * The words a search uses: letters and digits, 3 or more, each once, without the words that
     * say "a meeting" rather than which one. Tokenised by hand: ICU and the JVM disagree on `\b`.
     */
    fun searchWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.length >= 3) words += word.toString()
            word.setLength(0)
        }
        for (c in text.lowercase()) if (c.isLetterOrDigit()) word.append(c) else flush()
        flush()
        return words.filterNot { it in NOISE }.distinct()
    }

    /** Mission-log decisions sharing a word with the meeting, newest first. */
    fun decisions(entries: List<LogEntry>, words: List<String>, max: Int = MAX_DECISIONS): List<LogEntry> {
        if (words.isEmpty()) return emptyList()
        val wanted = words.toSet()
        return entries
            .filter { it.kind == LogKind.DECISION }
            .filter { entry -> searchWords(entry.text + " " + entry.tags.joinToString(" ") + " " + entry.reference.orEmpty()).any { it in wanted } }
            .sortedByDescending { it.at }
            .take(max)
    }

    /**
     * The meeting note (the toolkit's, `MeetingRepository.create`) with the prep added before its
     * agenda: the notes as links, the latest decisions. Vault content, so French.
     */
    fun withPrep(note: String, notes: List<String>, decisions: List<LogEntry>): String {
        val section = prepSection(notes, decisions)
        val agenda = note.indexOf(AGENDA)
        if (agenda < 0) return note.trimEnd() + "\n\n" + section + "\n"
        return note.substring(0, agenda) + section + "\n\n" + note.substring(agenda)
    }

    private fun prepSection(notes: List<String>, decisions: List<LogEntry>): String = buildString {
        append("## Préparation")
        if (notes.isEmpty() && decisions.isEmpty()) {
            append("\n\nRien dans le coffre à propos de cette réunion.")
            return@buildString
        }
        if (notes.isNotEmpty()) {
            append("\n\nNotes liées :\n")
            append(notes.joinToString("\n") { "- [[${it.removeSuffix(".md")}]]" })
        }
        if (decisions.isNotEmpty()) {
            append("\n\nDernières décisions :\n")
            append(decisions.joinToString("\n") { "- ${it.at.toLocalDate()} — ${it.text}" })
        }
    }

    /** The toolkit's meeting notes open on their agenda (`MeetingFormat.newMeeting`). */
    private const val AGENDA = "## Ordre du jour"

    /** Words that say "a meeting", not which one (the titles are French or English). */
    private val NOISE = setOf(
        "réunion", "reunion", "réu", "meeting", "point", "sync", "synchro", "weekly", "hebdo", "hebdomadaire",
        "daily", "quotidien", "call", "appel", "visio", "rdv", "avec", "with", "the", "les", "des", "and",
        "pour", "for", "sur", "update", "team", "équipe", "equipe", "teams", "zoom", "meet"
    )
}
