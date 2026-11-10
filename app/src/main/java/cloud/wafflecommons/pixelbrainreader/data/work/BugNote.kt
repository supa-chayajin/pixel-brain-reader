package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDateTime

/**
 * C8's bug note: the vault's `T_Bug_Report` template filled with the error, or a built-in note with
 * the same sections. The added sections are French, like every generated document.
 */
object BugNote {

    const val TEMPLATE_PATH = "99_System/Templates/T_Bug_Report.md"
    const val TRACE_LINES = 80
    private const val TITLE_CHARS = 60

    /** The project's `Bugs/` folder when the error belongs to a project (D3), otherwise the inbox. */
    fun folder(projectRoot: String?): String = if (projectRoot != null) "$projectRoot/Bugs" else "00_Inbox"

    /** The note's title: the error's type and message, short. */
    fun title(trace: ErrorTrace): String {
        val raw = listOfNotNull(trace.type, trace.message.takeIf { it.isNotBlank() }).joinToString(": ").ifBlank { trace.headline }
        // The title lands in a double-quoted YAML string: no `"`, and no `\` (PHP namespaces) to escape.
        val oneLine = raw.replace(Regex("""\s+"""), " ").replace('"', '\'').replace('\\', '/').trim()
        return if (oneLine.length <= TITLE_CHARS) oneLine else oneLine.take(TITLE_CHARS - 1).trimEnd() + "…"
    }

    /** `<folder>/<date> Bug <words>.md`: a file name any system takes. */
    fun path(folder: String, now: LocalDateTime, trace: ErrorTrace): String {
        val words = title(trace).replace(Regex("""[^A-Za-z0-9À-ÿ]+"""), " ").trim().split(' ').take(6).joinToString(" ")
        return "$folder/${now.toLocalDate()} Bug ${words.ifBlank { "erreur" }}.md"
    }

    fun build(
        template: String?,
        path: String,
        trace: ErrorTrace,
        raw: String,
        explanation: String?,
        isGeneralKnowledge: Boolean,
        occurrences: List<ErrorLookup.Occurrence>,
        now: LocalDateTime
    ): String {
        val title = title(trace)
        val explained = explanationBlock(explanation, isGeneralKnowledge, trace)
        val sections = seenBefore(occurrences) + traceSection(raw)
        if (template == null) return builtIn(title, path, now, explained, sections)

        var note = TemplateEngine.apply(template, title, now = now)
        val type = VaultSchema.typeFor(path)
        if (FrontmatterLines.get(note, "type") != null) note = FrontmatterLines.set(note, "type", type)
        if (FrontmatterLines.get(note, "status") != null) note = FrontmatterLines.set(note, "status", VaultSchema.defaultStatus(type))
        note = insertUnderDescription(note, explained)
        return note.trimEnd() + "\n" + sections
    }

    private fun builtIn(title: String, path: String, now: LocalDateTime, explained: String, sections: String): String =
        VaultSchema.frontmatter(title = "🐞 Bug: $title", path = path, date = now.toLocalDate(), tags = listOf("bug")) +
            "\n# 🐞 Bug: $title\n\n## 📝 Description\n\n" + explained + "\n" + sections

    private fun explanationBlock(explanation: String?, isGeneralKnowledge: Boolean, trace: ErrorTrace): String {
        val headline = "`" + trace.headline.replace('`', '\'') + "`"
        if (explanation.isNullOrBlank()) return headline + "\n"
        val label = if (isGeneralKnowledge) "Explication (IA sur l'appareil, connaissances générales : pas tes notes)" else "Explication (IA sur l'appareil)"
        return headline + "\n\n**" + label + "** : " + explanation.trim() + "\n"
    }

    /** The explanation right under the template's Description heading; at the end when there is none. */
    private fun insertUnderDescription(note: String, block: String): String {
        val lines = note.lines().toMutableList()
        val heading = lines.indexOfFirst { it.startsWith("#") && it.contains("Description", ignoreCase = true) }
        if (heading < 0) return note.trimEnd() + "\n\n## 📝 Description\n\n" + block
        lines.add(heading + 1, "")
        lines.add(heading + 2, block.trimEnd())
        return lines.joinToString("\n")
    }

    private fun seenBefore(occurrences: List<ErrorLookup.Occurrence>): String {
        val links = occurrences.map { it.path }.filterNot(::isPrivate).map { it.removeSuffix(".md") }.distinct()
        return "\n## 🔎 Déjà vu dans le coffre\n\n" +
            (if (links.isEmpty()) "Pas vu dans tes notes.\n" else links.joinToString("\n", postfix = "\n") { "- [[$it]]" })
    }

    private fun traceSection(raw: String): String {
        val lines = raw.trim().lines()
        val kept = lines.take(TRACE_LINES).joinToString("\n")
        val more = if (lines.size > TRACE_LINES) "\n(${lines.size - TRACE_LINES} lignes de plus non recopiées)" else ""
        val fence = if (kept.contains("```")) "~~~" else "```"
        return "\n## 📜 Trace\n\n$fence\n$kept\n$fence$more\n"
    }

    private fun isPrivate(path: String): Boolean =
        VaultSchema.typeFor(path) == VaultSchema.TYPE_PRIVATE || path.endsWith(".md.enc", ignoreCase = true)
}
