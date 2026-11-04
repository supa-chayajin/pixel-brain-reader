package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One hand-off line: `- [ ] yyyy-MM-dd HH:mm — text`. */
data class DeskHandoffItem(val line: String, val text: String, val at: LocalDateTime?, val done: Boolean)

/**
 * Hand-off to desktop sessions (C13): `<project>/Handoff.md`, one checkbox per note written on
 * the phone. The desktop's SessionStart hook (`second-brain/scripts/handoff.sh` →
 * `worktrace.py handoff`) prints the open ones on the machine whose allow-list owns the project;
 * ticking a line (phone or desktop) closes it. The file follows the vault DTO and is edited one
 * line at a time, never re-serialized.
 */
object DeskHandoffFormat {
    const val FILE_NAME = "Handoff.md"

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val itemLine = Regex("""^\s*- \[([ xX])] (.+)$""")

    fun path(root: String): String = "$root/$FILE_NAME"

    fun isHandoff(path: String): Boolean = path.substringAfterLast('/') == FILE_NAME

    fun newNote(projectTitle: String, today: LocalDate): String = buildString {
        append("---\n")
        append("title: \"🤝 ").append(projectTitle.replace("\"", "'")).append(" — Hand-offs\"\n")
        append("date_created: '").append(today).append("'\n")
        append("date_updated: '").append(today).append("'\n")
        append("type: note\n")
        append("status: 🏗️ wip\n")
        append("tags:\n  - handoff\n")
        append("aliases: []\n")
        append("---\n\n")
        append("# 🤝 Hand-offs\n\n")
        append("> Written from the phone, shown at the start of a desktop session on the machine that owns this project. ")
        append("Tick a line when it's done.\n\n")
    }

    /** One line, whatever was typed: line breaks would split the item. */
    fun line(at: LocalDateTime, text: String): String =
        "- [ ] ${at.format(stamp)} — ${text.replace(Regex("""\s*[\r\n]+\s*"""), " ").trim()}"

    /** [content] with a new open item appended (a new note when [content] is null). */
    fun add(content: String?, projectTitle: String, at: LocalDateTime, text: String): String {
        val base = content ?: newNote(projectTitle, at.toLocalDate())
        val withBreak = if (base.endsWith("\n")) base else "$base\n"
        return touch(withBreak + line(at, text) + "\n", at.toLocalDate())
    }

    fun items(content: String): List<DeskHandoffItem> = content.lines().mapNotNull { raw ->
        val m = itemLine.find(raw) ?: return@mapNotNull null
        val body = m.groupValues[2].trim()
        val at = runCatching { LocalDateTime.parse(body.take(16), stamp) }.getOrNull()
        val text = if (at != null) body.drop(16).trimStart(' ', '—', '-').trim() else body
        DeskHandoffItem(raw, text, at, done = m.groupValues[1] != " ")
    }

    /** Ticks exactly [line] (the one that was read); null when it is no longer there. */
    fun complete(content: String, line: String, today: LocalDate): String? {
        val lines = content.split("\n").toMutableList()
        val i = lines.indexOf(line)
        if (i < 0 || !lines[i].contains("- [ ] ")) return null
        lines[i] = lines[i].replaceFirst("- [ ] ", "- [x] ")
        return touch(lines.joinToString("\n"), today)
    }

    private fun touch(content: String, today: LocalDate): String =
        if (FrontmatterLines.hasFrontmatter(content)) FrontmatterLines.set(content, "date_updated", "'$today'") else content
}
