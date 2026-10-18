package cloud.wafflecommons.pixelbrainreader.data.vault

import java.time.LocalDate

/** One rule a note breaks; [fixable] when the app can repair it with a one-line edit. */
data class LintIssue(val rule: String, val message: String, val fixable: Boolean)

/**
 * The vault's linting contract on the phone (E7), advisory: what the desktop linter
 * (`frontmatter_lint.py`) would say about a note, and the few repairs that are safe as surgical
 * edits — missing standard keys, quoting, line endings, trailing spaces and the final newline.
 * Everything else (order, vocabulary, headings, links) is reported, never rewritten.
 */
object VaultLint {

    private val TYPES = setOf("inbox", "log", "projet", "area", "ressource", "archive", "private", "system", "note")
    private val SINGLE_QUOTED_DATE = Regex("""^'\d{4}-\d{2}-\d{2}'$""")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val WORKLOG = Regex("""(^|/)Worklog/\d{4}-\d{2}-\d{2}\.md$""")

    /** R6 and the generated files: never linted, never touched. */
    fun isExcluded(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return path == "00_Dashboard.md" || path.endsWith(".enc") || path.endsWith(".canvas") || path.endsWith(".base") ||
            !path.endsWith(".md") || name.startsWith(".") || path.split('/').any { it.startsWith(".") } ||
            WORKLOG.containsMatchIn(path)
    }

    /** [linkResolves] answers for a link key (`LinkExtractor` keys: lower-case file name, no `.md`). */
    fun check(path: String, content: String, linkResolves: (String) -> Boolean): List<LintIssue> {
        if (isExcluded(path)) return emptyList()
        val issues = mutableListOf<LintIssue>()
        if (VaultSchema.isDailyNote(path)) {
            val keys = FrontmatterLines.keys(content)
            if (keys.isNotEmpty() && keys != listOf("date")) {
                issues += LintIssue("R2", "A daily note's frontmatter holds only date: (found ${keys.joinToString()})", fixable = false)
            }
        } else {
            frontmatterIssues(path, content, issues)
        }
        bodyIssues(content, linkResolves, issues)
        return issues
    }

    private fun frontmatterIssues(path: String, content: String, issues: MutableList<LintIssue>) {
        val keys = FrontmatterLines.keys(content)
        val missing = VaultSchema.STANDARD_KEYS.filter { it !in keys }
        if (missing.isNotEmpty()) issues += LintIssue("R1", "Missing ${missing.joinToString()}", fixable = true)
        val present = keys.filter { it in VaultSchema.STANDARD_KEYS }
        if (present != VaultSchema.STANDARD_KEYS.filter { it in present }) {
            issues += LintIssue("R1", "Standard keys out of order (title, date_created, date_updated, type, status, tags, aliases)", fixable = false)
        }
        val titleLine = rawValue(content, "title")
        if (titleLine != null && !(titleLine.startsWith("\"") && titleLine.endsWith("\"") && titleLine.length > 2)) {
            issues += LintIssue("R1", "title is not double-quoted", fixable = titleLine.isNotBlank())
        }
        for (key in listOf("date_created", "date_updated")) {
            val raw = rawValue(content, key) ?: continue
            if (!SINGLE_QUOTED_DATE.matches(raw)) issues += LintIssue("R1", "$key is not 'YYYY-MM-DD'", fixable = ISO_DATE.containsMatchIn(raw))
        }
        FrontmatterLines.get(content, "type")?.let { type ->
            if (type !in TYPES) issues += LintIssue("R1", "type \"$type\" is not in the vocabulary", fixable = false)
            else if (type != VaultSchema.typeFor(path)) issues += LintIssue("R4", "The folder implies type ${VaultSchema.typeFor(path)} (found $type)", fixable = false)
        }
        FrontmatterLines.get(content, "status")?.let { status ->
            if (status.firstOrNull()?.isLetterOrDigit() != false) issues += LintIssue("R1", "status \"$status\" is not emoji-prefixed", fixable = false)
        }
        val tags = FrontmatterLines.getList(content, "tags")
        if (tags.any { it != it.lowercase() }) issues += LintIssue("R1", "Tags must be lowercase", fixable = false)
    }

    private fun bodyIssues(content: String, linkResolves: (String) -> Boolean, issues: MutableList<LintIssue>) {
        val body = stripFrontmatter(content)
        var inFence = false
        var h1 = 0
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            if (!inFence && (trimmed == "#" || trimmed.startsWith("# "))) h1++
        }
        if (h1 != 1) issues += LintIssue("R3", if (h1 == 0) "No # H1" else "$h1 H1 headings (exactly one expected)", fixable = false)
        val dead = LinkExtractor.extract(content)
            .filter { it.kind != "md" && it.targetKey.isNotEmpty() && !linkResolves(it.targetKey) }
            .map { it.target }
            .distinct()
        if (dead.isNotEmpty()) issues += LintIssue("R3", "Links that don't resolve: ${dead.take(5).joinToString { "[[$it]]" }}" + if (dead.size > 5) "…" else "", fixable = false)
        if (content.contains('\r')) issues += LintIssue("R3", "CRLF line endings", fixable = true)
        if (hasTrailingWhitespace(content)) issues += LintIssue("R3", "Trailing whitespace", fixable = true)
        if (content.isNotEmpty() && !content.endsWith("\n")) issues += LintIssue("R3", "No newline at the end of the file", fixable = true)
    }

    /**
     * Applies the safe repairs: LF endings, no trailing spaces outside code, a final newline, the
     * missing standard keys at their place (defaults from the folder and the H1), title quoting
     * and date quoting. Returns [content] unchanged for excluded files and daily notes' keys.
     */
    fun fix(path: String, content: String, today: LocalDate): String {
        if (isExcluded(path)) return content
        var text = content.replace("\r\n", "\n").replace('\r', '\n')
        var inFence = false
        text = text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            if (inFence) line else line.trimEnd()
        }
        if (!VaultSchema.isDailyNote(path)) text = fixFrontmatter(path, text, today)
        if (!text.endsWith("\n")) text += "\n"
        return text
    }

    private fun fixFrontmatter(path: String, content: String, today: LocalDate): String {
        var text = content
        val keys = FrontmatterLines.keys(text)
        val type = VaultSchema.typeFor(path)
        val title = FrontmatterLines.get(text, "title")?.takeIf { it.isNotBlank() }
            ?: stripFrontmatter(text).lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: path.substringAfterLast('/').removeSuffix(".md")
        val defaults = mapOf(
            "title" to VaultSchema.doubleQuoted(title),
            "date_created" to VaultSchema.singleQuoted(today.toString()),
            "date_updated" to VaultSchema.singleQuoted(today.toString()),
            "type" to type,
            "status" to VaultSchema.defaultStatus(type),
            "tags" to "[]",
            "aliases" to "[]"
        )
        // In R1 order, so each insertion finds its predecessors in place.
        for (key in VaultSchema.STANDARD_KEYS) {
            if (key !in keys) text = FrontmatterLines.set(text, key, defaults.getValue(key))
        }
        rawValue(text, "title")?.let { raw ->
            if (raw.isNotBlank() && !(raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2)) {
                text = FrontmatterLines.set(text, "title", VaultSchema.doubleQuoted(FrontmatterLines.get(text, "title").orEmpty()))
            }
        }
        for (key in listOf("date_created", "date_updated")) {
            val raw = rawValue(text, key) ?: continue
            if (!SINGLE_QUOTED_DATE.matches(raw)) {
                ISO_DATE.find(raw)?.value?.let { date -> text = FrontmatterLines.set(text, key, VaultSchema.singleQuoted(date)) }
            }
        }
        return text
    }

    /** Trailing spaces or tabs on a line outside code fences (inside them they may matter). */
    private fun hasTrailingWhitespace(content: String): Boolean {
        var inFence = false
        return content.lines().any { raw ->
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            !inFence && line.isNotEmpty() && line != line.trimEnd()
        }
    }

    /** The value of [key] as written (quotes kept), or null. */
    private fun rawValue(content: String, key: String): String? =
        content.lines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.takeIf { FrontmatterLines.hasFrontmatter(content) }

    private fun stripFrontmatter(content: String): String {
        if (!FrontmatterLines.hasFrontmatter(content)) return content
        val lines = content.lines()
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return content
        return lines.drop(close + 1).joinToString("\n")
    }
}
