package cloud.wafflecommons.pixelbrainreader.data.editor

import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the text right before the caret is asking for. [start] is where the replaced token begins. */
sealed class EditorTrigger {
    abstract val query: String
    abstract val start: Int

    /** `/word` at the start of a line or after a space. */
    data class Slash(override val query: String, override val start: Int) : EditorTrigger()

    /** `[[name` not closed yet on this line. */
    data class Link(override val query: String, override val start: Int) : EditorTrigger()
}

/** A `/` command: [text] replaces the `/word`; the caret lands [caretFromEnd] characters before its end. */
data class SlashCommand(val id: String, val label: String, val text: String, val caretFromEnd: Int = 0)

/** An edit to apply: the new text and where the caret goes. */
data class AssistEdit(val text: String, val caret: Int)

/**
 * The editor's helpers (E1, E2): `/` commands that insert dates, checkboxes, actions, callouts,
 * code blocks and tables, and `[[` completion from the vault's note names. Pure: the editor hands
 * it the text and the caret.
 */
object EditorAssist {

    private const val MAX_QUERY = 40

    fun trigger(text: String, caret: Int): EditorTrigger? {
        if (caret < 0 || caret > text.length) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val before = text.substring(lineStart, caret)
        val open = before.lastIndexOf("[[")
        if (open >= 0) {
            val query = before.substring(open + 2)
            if (!query.contains("]]") && !query.contains('|') && query.length <= MAX_QUERY) return EditorTrigger.Link(query, lineStart + open)
        }
        val slash = before.lastIndexOf('/')
        if (slash >= 0) {
            val query = before.substring(slash + 1)
            val boundary = slash == 0 || before[slash - 1].isWhitespace()
            if (boundary && query.length <= MAX_QUERY && query.all { it.isLetterOrDigit() }) return EditorTrigger.Slash(query, lineStart + slash)
        }
        return null
    }

    fun commands(now: LocalDateTime): List<SlashCommand> {
        val today = now.toLocalDate()
        val date = today.toString()
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return listOf(
            SlashCommand("date", "Today's date", date),
            SlashCommand("time", "Current time", time),
            SlashCommand("now", "Date and time", "$date $time"),
            SlashCommand("todo", "Checkbox", "- [ ] "),
            SlashCommand("action", "My action (@moi)", "- [ ] @moi "),
            SlashCommand("due", "Due in a week", "📅 ${today.plusDays(7)}"),
            SlashCommand("callout", "Callout", "> [!NOTE]\n> "),
            SlashCommand("code", "Code block", "```\n\n```", caretFromEnd = 4),
            SlashCommand("table", "Table", "| Colonne | Colonne |\n|---|---|\n|  |  |"),
            SlashCommand("h2", "Heading", "## "),
            SlashCommand("quote", "Quote", "> "),
            SlashCommand("link", "Link to a note", "[["),
            SlashCommand("hr", "Separator", "---\n")
        )
    }

    /** Commands whose id starts with [query] (all of them for an empty query). */
    fun matchingCommands(query: String, now: LocalDateTime): List<SlashCommand> =
        commands(now).filter { it.id.startsWith(query.lowercase()) }

    /** Note names containing [query], accents and case aside: prefix matches first, at most [limit]. */
    fun matchingNotes(query: String, names: List<String>, limit: Int = 8): List<String> {
        val q = fold(query.trim())
        if (q.isEmpty()) return names.take(limit)
        return names.asSequence()
            .map { it to fold(it) }
            .filter { (_, folded) -> q in folded }
            .sortedWith(compareBy<Pair<String, String>> { !it.second.startsWith(q) }.thenBy { it.first.length })
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** Replaces the trigger's token (from [EditorTrigger.start] to the caret) by [command]'s text. */
    fun apply(text: String, caret: Int, trigger: EditorTrigger, command: SlashCommand): AssistEdit {
        val updated = text.substring(0, trigger.start) + command.text + text.substring(caret)
        return AssistEdit(updated, trigger.start + command.text.length - command.caretFromEnd)
    }

    /** Completes `[[query` into `[[name]]`, reusing a `]]` already typed after the caret. */
    fun complete(text: String, caret: Int, trigger: EditorTrigger, name: String): AssistEdit {
        val after = text.substring(caret)
        val tail = if (after.startsWith("]]")) after.substring(2) else after
        val link = "[[$name]]"
        return AssistEdit(text.substring(0, trigger.start) + link + tail, trigger.start + link.length)
    }

    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
}
