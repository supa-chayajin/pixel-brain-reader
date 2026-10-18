package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlock
import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlocks
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One step of a runbook: a `##`/`###` section, or a top-level list item when the note has no sections. */
data class RunbookStep(val title: String, val body: String, val code: List<CodeBlock>)

enum class StepStatus(val french: String) { DONE("fait"), SKIPPED("passé"), FAILED("échec") }

data class StepResult(val step: RunbookStep, val status: StepStatus, val at: LocalDateTime, val note: String?)

/**
 * Runbooks (E4): notes tagged `runbook` (or under a `Runbooks` folder) played step by step on the
 * phone. Each step's status, time and note become an incident log, in French, in the inbox.
 */
object Runbooks {

    val RUNBOOK_TAGS = setOf("runbook", "runbooks", "procedure", "procédure")
    private val ORDERED_ITEM = Regex("""^\d+[.)]\s+""")

    fun isRunbook(note: WorkNote): Boolean =
        note.tags.any { it.lowercase().removePrefix("#") in RUNBOOK_TAGS } || note.path.split('/').any { it.equals("Runbooks", ignoreCase = true) }

    fun steps(path: String, content: String): List<RunbookStep> {
        val body = stripFrontmatter(content)
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        var inFence = false
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            val level = if (!inFence && trimmed.startsWith("#")) trimmed.takeWhile { it == '#' }.length else 0
            if (level in 2..3 && trimmed.getOrNull(level)?.isWhitespace() == true) {
                sections += trimmed.drop(level).trim() to StringBuilder()
            } else if (sections.isNotEmpty()) {
                sections.last().second.append(line).append('\n')
            }
        }
        if (sections.isNotEmpty()) {
            return sections.map { (title, text) -> RunbookStep(title, text.toString().trim(), CodeBlocks.extract(path, text.toString())) }
        }
        // No sections: each top-level item of the first list is a step.
        return body.lines()
            .filter { it.startsWith("- ") || it.startsWith("* ") || ORDERED_ITEM.containsMatchIn(it) }
            .map { item ->
                val title = item.replaceFirst(ORDERED_ITEM, "").removePrefix("- ").removePrefix("* ").removePrefix("[ ] ").removePrefix("[x] ").trim()
                RunbookStep(title, "", emptyList())
            }
    }

    fun incidentPath(runbook: WorkNote, startedAt: LocalDateTime): String =
        "${VaultSchema.INBOX_FOLDER}/Incident ${runbook.linkName} ${startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'mm"))}.md"

    fun incidentLog(runbook: WorkNote, startedAt: LocalDateTime, results: List<StepResult>, finishedAt: LocalDateTime, notes: String?, today: LocalDate): String = buildString {
        val path = incidentPath(runbook, startedAt)
        append(VaultSchema.frontmatter(title = "Incident — ${runbook.title} — ${startedAt.toLocalDate()}", path = path, date = today, tags = listOf("incident", "runbook")))
        append("# Incident — ").append(runbook.title).append("\n\n")
        append("> Déroulé du runbook [[").append(runbook.linkName).append("]] du ")
            .append(startedAt.format(STAMP)).append(" au ").append(finishedAt.format(STAMP)).append(".\n\n")
        val done = results.count { it.status == StepStatus.DONE }
        val failed = results.count { it.status == StepStatus.FAILED }
        val skipped = results.count { it.status == StepStatus.SKIPPED }
        append("**Bilan** : ").append(done).append(" fait · ").append(failed).append(" en échec · ").append(skipped).append(" passé\n\n")
        append("## Étapes\n\n")
        results.forEachIndexed { index, result ->
            append(index + 1).append(". ").append(if (result.status == StepStatus.DONE) "✅" else if (result.status == StepStatus.FAILED) "❌" else "⏭️")
                .append(' ').append(result.step.title).append(" — ").append(result.status.french).append(" à ").append(result.at.format(TIME))
            result.note?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim().replace('\n', ' ')) }
            append('\n')
        }
        append("\n## Notes\n\n").append(notes?.trim()?.takeIf { it.isNotEmpty() } ?: "_Aucune._").append("\n\n")
        append("## Suites à donner\n\n- [ ] @moi \n")
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    private fun stripFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content
        val end = content.indexOf("\n---", 3)
        return if (end < 0) content else content.substring(end + 4)
    }
}
