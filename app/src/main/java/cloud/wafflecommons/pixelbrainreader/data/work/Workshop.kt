package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The workshop kit (W2, 12.21; the owner's D4): a meeting note's agenda (`## Ordre du jour`) run as
 * timed steps, a capture board of four columns filled along the way, and at the end a French
 * synthesis note beside the meeting, linked from it. The draft lives on the phone until then.
 * Pure: the screen keeps time, the repository writes.
 */
object Workshop {

    /** A meeting without a recorded length runs this long. */
    const val DEFAULT_MINUTES = 60
    const val MIN_STEP_MINUTES = 5

    /** The board's columns. The keys are persisted in the draft: never rename one. */
    enum class Column(val key: String, val label: String, val heading: String) {
        IDEA("idea", "Ideas", "Idées"),
        DECISION("decision", "Decisions", "Décisions"),
        ACTION("action", "Actions", "Actions"),
        QUESTION("question", "Questions", "Questions ouvertes");

        companion object {
            fun of(key: String): Column? = entries.firstOrNull { it.key == key }
        }
    }

    @Serializable
    data class Step(val title: String, val minutes: Int, val startedAt: Long? = null, val endedAt: Long? = null)

    @Serializable
    data class Card(val id: String, val column: String, val text: String, val step: Int)

    /** A workshop under way: one at a time, kept on the phone. */
    @Serializable
    data class Draft(
        val notePath: String,
        val title: String,
        val steps: List<Step>,
        /** The step under way; [steps].size once the last one is over. */
        val current: Int = 0,
        val cards: List<Card> = emptyList()
    ) {
        val started: Boolean get() = steps.firstOrNull()?.startedAt != null
        val over: Boolean get() = current >= steps.size
    }

    private val AGENDA_HEADINGS = setOf("ordre du jour", "agenda")
    private val ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?(.*)$""")

    /** The list items under "Ordre du jour" (or "Agenda"), up to the next heading; empty ones left out. */
    fun agenda(content: String): List<String> {
        val items = mutableListOf<String>()
        var inSection = false
        var inCode = false
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) inCode = !inCode
            if (inCode) continue
            if (trimmed.startsWith("#")) {
                inSection = trimmed.trimStart('#').trim().lowercase() in AGENDA_HEADINGS
                continue
            }
            if (!inSection) continue
            ITEM.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { items += it }
        }
        return items
    }

    /** [agenda] as steps sharing [total] minutes evenly (at least [MIN_STEP_MINUTES] each), the remainder to the first ones. */
    fun steps(agenda: List<String>, total: Int): List<Step> {
        if (agenda.isEmpty()) return emptyList()
        val budget = maxOf(total, agenda.size * MIN_STEP_MINUTES)
        val each = budget / agenda.size
        val extra = budget % agenda.size
        return agenda.mapIndexed { index, title -> Step(title, each + if (index < extra) 1 else 0) }
    }

    /** Starts the first step at [now]. */
    fun start(draft: Draft, now: Long): Draft =
        if (draft.started || draft.steps.isEmpty()) draft
        else draft.copy(steps = draft.steps.mapIndexed { i, s -> if (i == 0) s.copy(startedAt = now) else s })

    /** Ends the step under way at [now] and starts the next one, if any. */
    fun next(draft: Draft, now: Long): Draft {
        if (!draft.started || draft.over) return draft
        val steps = draft.steps.mapIndexed { i, s ->
            when (i) {
                draft.current -> s.copy(endedAt = now)
                draft.current + 1 -> s.copy(startedAt = now)
                else -> s
            }
        }
        return draft.copy(steps = steps, current = draft.current + 1)
    }

    /** Seconds left in [step] at [now]; negative once over its time. */
    fun secondsLeft(step: Step, now: Long): Long {
        val started = step.startedAt ?: return step.minutes * 60L
        return step.minutes * 60L - (now - started) / 1000
    }

    /** The minutes a step actually took, rounded; null while it hasn't ended. */
    fun minutesTaken(step: Step): Int? {
        val started = step.startedAt ?: return null
        val ended = step.endedAt ?: return null
        return ((ended - started + 30_000) / 60_000).toInt()
    }

    fun add(draft: Draft, id: String, column: Column, text: String): Draft {
        val line = oneLine(text)
        if (line.isEmpty()) return draft
        return draft.copy(cards = draft.cards + Card(id, column.key, line, draft.current.coerceAtMost(draft.steps.size - 1).coerceAtLeast(0)))
    }

    fun remove(draft: Draft, id: String): Draft = draft.copy(cards = draft.cards.filterNot { it.id == id })

    fun cardsIn(draft: Draft, column: Column): List<Card> = draft.cards.filter { it.column == column.key }

    /** The synthesis note's path: beside the meeting note. */
    fun synthesisPath(meetingPath: String): String = meetingPath.removeSuffix(".md") + " (synthèse).md"

    /** Nano's French prompt for the summary: the board, never more than [PROMPT_BUDGET] characters of it. */
    fun summaryPrompt(draft: Draft): String = buildString {
        append("Voici le tableau d'un atelier intitulé « ").append(draft.title).append(" ».\n")
        Column.entries.forEach { column ->
            val cards = cardsIn(draft, column)
            if (cards.isNotEmpty()) {
                append(column.heading).append(" :\n")
                cards.forEach { append("- ").append(it.text).append('\n') }
            }
        }
    }.take(PROMPT_BUDGET).let { board ->
        board + "\nRésume cet atelier en trois à cinq phrases : ce qui a été décidé, ce qui reste à faire et ce qui reste ouvert. " +
            "N'invente rien qui ne soit pas dans le tableau. " + AiLanguage.DIRECTIVE
    }

    /** The French synthesis note: the board by column, the run of the agenda, and Nano's [summary] when there is one. */
    fun synthesis(draft: Draft, path: String, date: LocalDate, summary: String?): String = buildString {
        val meeting = draft.notePath.substringAfterLast('/').removeSuffix(".md")
        val title = "Synthèse — ${draft.title}"
        append(VaultSchema.frontmatter(title = title, path = path, date = date, tags = listOf("atelier")))
        append("# ").append(title).append("\n\n")
        val planned = draft.steps.sumOf { it.minutes }
        val taken = draft.steps.mapNotNull(::minutesTaken).takeIf { it.size == draft.steps.size }?.sum()
        append("**Atelier du** ").append(date)
        append(" · ").append(taken?.let { "$it min (prévu $planned)" } ?: "prévu $planned min")
        append(" · **Réunion** : [[").append(meeting).append("]]\n")
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n## Résumé\n\n").append(it).append('\n') }
        Column.entries.forEach { column ->
            val cards = cardsIn(draft, column)
            if (cards.isEmpty()) return@forEach
            append("\n## ").append(column.heading).append("\n\n")
            cards.forEach { card ->
                append(if (column == Column.ACTION) "- [ ] " else "- ").append(card.text).append('\n')
            }
        }
        append("\n## Déroulé\n\n")
        draft.steps.forEach { step ->
            append("- ").append(step.title).append(" — ")
            append(minutesTaken(step)?.let { "$it min (prévu ${step.minutes})" } ?: "prévu ${step.minutes} min")
            append('\n')
        }
    }

    /**
     * The meeting note with a link to its synthesis, under its `## Atelier` heading (added at the
     * end when missing). A note that already links it is returned as it is.
     */
    fun withSynthesisLink(content: String, synthesisPath: String): String {
        val link = "[[" + synthesisPath.substringAfterLast('/').removeSuffix(".md") + "]]"
        if (content.contains(link)) return content
        val line = "- Synthèse de l'atelier → $link"
        val lines = content.trimEnd('\n').lines().toMutableList()
        val heading = lines.indexOfFirst { it.trim().equals(SECTION, ignoreCase = true) }
        if (heading < 0) return lines.joinToString("\n") + "\n\n" + SECTION + "\n\n" + line + "\n"
        var end = (heading + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("#") } ?: lines.size
        while (end - 1 > heading && lines[end - 1].isBlank()) end--
        val inserted = if (end == heading + 1) listOf("", line) else listOf(line)
        lines.addAll(end, inserted)
        // The next heading keeps its blank line above it.
        val after = end + inserted.size
        if (after < lines.size && lines[after].isNotBlank()) lines.add(after, "")
        return lines.joinToString("\n") + "\n"
    }

    /** A card's text on one line: the synthesis keeps one item a line. */
    fun oneLine(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private const val SECTION = "## Atelier"
    private const val PROMPT_BUDGET = 3_000
}
