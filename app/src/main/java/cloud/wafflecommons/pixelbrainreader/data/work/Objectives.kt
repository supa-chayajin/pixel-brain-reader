package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A checklist item of an objective note: `- [ ] Text 📅 2026-12-01` (or `[due:: 2026-12-01]`). */
data class Milestone(val text: String, val done: Boolean, val due: LocalDate?, val sourceLine: String)

/** An objective note (tagged `#objectif`), its milestones and the log entries that serve it as material. */
data class Objective(
    val note: WorkNote,
    val milestones: List<Milestone>,
    val material: List<LogEntry>,
    /** Projects linked with the objective: a link either way with one of their notes, or a shared tag. */
    val linkedProjects: List<ProjectSummary> = emptyList(),
    /** Notes outside the projects that link to the objective or that it links to (daily notes aside). */
    val linkedNotes: List<WorkNote> = emptyList()
) {
    val done: Int get() = milestones.count { it.done }
    val progress: Float get() = if (milestones.isEmpty()) 0f else done.toFloat() / milestones.size
    val nextDue: Milestone? get() = milestones.filter { !it.done && it.due != null }.minByOrNull { it.due!! }

    /**
     * Nothing moved for [ObjectiveFormat.STALE_DAYS]: neither the note, nor a log entry tagged for
     * it, nor a linked project (its last worklog or note update).
     */
    fun stale(today: LocalDate): Boolean {
        val lastNote = note.updated ?: note.created
        val lastLog = material.maxOfOrNull { it.at.toLocalDate() }
        val lastProject = linkedProjects.mapNotNull { it.lastActivity }.filter { !it.isAfter(today) }.maxOrNull()
        val last = listOfNotNull(lastNote, lastLog, lastProject).maxOrNull() ?: return true
        return last.isBefore(today.minusDays(ObjectiveFormat.STALE_DAYS))
    }
}

/** The weekly slot protected for objectives, and whether to be nudged when it starts. Synced in the vault. */
@Serializable
data class ObjectiveSettings(
    val slotEnabled: Boolean = false,
    val slotDay: Int = DayOfWeek.FRIDAY.value,
    val slotStart: String = "14:00",
    val slotMinutes: Int = 90
)

/**
 * Objectives (#5): notes tagged `#objectif` (or `#objective`), their `- [ ]` milestones, and the
 * mission-log entries sharing one of their other tags as material for the review.
 */
object ObjectiveFormat {

    const val STALE_DAYS = 21L
    val OBJECTIVE_TAGS = setOf("objectif", "objectifs", "objective", "objectives")

    private val TASK = Regex("""^\s*[-*+] \[([ xX])\] (.*)$""")
    private val DUE_EMOJI = Regex("""📅\s*(\d{4}-\d{2}-\d{2})""")
    private val DUE_FIELD = Regex("""\[due::\s*(\d{4}-\d{2}-\d{2})\]""")
    private val SPACES = Regex("""\s+""")

    fun isObjective(note: WorkNote): Boolean = note.tags.any { it.lowercase().removePrefix("#") in OBJECTIVE_TAGS }

    /** The tags whose log entries count as material: the note's tags but the objective tags. */
    fun materialTags(note: WorkNote): Set<String> =
        note.tags.map { it.lowercase().removePrefix("#") }.filter { it !in OBJECTIVE_TAGS }.toSet()

    /** The note's checklist, outside code fences. */
    fun milestones(content: String): List<Milestone> {
        var inFence = false
        return content.lines().mapNotNull { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                return@mapNotNull null
            }
            if (inFence) return@mapNotNull null
            val match = TASK.matchEntire(line) ?: return@mapNotNull null
            val body = match.groupValues[2]
            val due = (DUE_EMOJI.find(body) ?: DUE_FIELD.find(body))?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val text = body.replace(DUE_EMOJI, "").replace(DUE_FIELD, "").replace(SPACES, " ").trim()
            Milestone(text, match.groupValues[1] != " ", due, line)
        }
    }

    /** [content] with [milestone]'s box flipped, or null when its line changed meanwhile. */
    fun toggle(content: String, milestone: Milestone): String? {
        val lines = content.lines().toMutableList()
        val index = lines.indexOf(milestone.sourceLine)
        if (index < 0) return null
        val line = lines[index]
        val box = line.indexOf("[", startIndex = line.indexOfFirst { it == '-' || it == '*' || it == '+' })
        lines[index] = line.substring(0, box) + (if (milestone.done) "[ ]" else "[x]") + line.substring(box + 3)
        return lines.joinToString("\n")
    }

    fun objective(note: WorkNote, log: List<LogEntry>): Objective {
        val tags = materialTags(note)
        return Objective(note, milestones(note.content), log.filter { entry -> entry.tags.any { it.lowercase() in tags } })
    }

    /**
     * [objective] with what it is linked with, from the links index ([links]: source path → target
     * key) read both ways: the notes it links to and the notes linking to it. A project counts when
     * one of its notes is such a note, or when its README carries one of the material tags that no
     * other project's README carries (a shared one, like `meta`, says nothing about the project).
     * Any other note counts unless it is a daily note (a journal entry, not a deliverable).
     * [notesByKey] resolves a link key to the vault's notes; [projects] are the visible ones.
     */
    fun withLinks(
        objective: Objective,
        links: List<Pair<String, String>>,
        notesByKey: Map<String, List<WorkNote>>,
        projects: List<ProjectSummary>
    ): Objective {
        val path = objective.note.path
        val key = LinkExtractor.keyOfPath(path)
        val outgoing = links.filter { it.first == path }.flatMap { (_, target) -> notesByKey[target].orEmpty() }.map { it.path }
        val incoming = links.filter { it.second == key && it.first != path }.map { it.first }
        val connected = (outgoing + incoming).toSet() - path
        val tags = materialTags(objective.note)
        val readmeTags = projects.associate { p -> p.root to p.tags.map { it.lowercase().removePrefix("#") }.toSet() }
        val tagUses = readmeTags.values.flatten().groupingBy { it }.eachCount()
        val linkedProjects = projects.filter { p ->
            connected.any { ProjectFormat.rootOf(it) == p.root } ||
                readmeTags[p.root].orEmpty().any { it in tags && tagUses[it] == 1 }
        }
        val byPath = notesByKey.values.flatten().associateBy { it.path }
        val linkedNotes = connected
            .filter { ProjectFormat.rootOf(it) == null && !VaultSchema.isDailyNote(it) }
            .mapNotNull { byPath[it] }
            .sortedWith(compareByDescending<WorkNote> { it.updated ?: it.created }.thenBy { it.title })
        return objective.copy(linkedProjects = linkedProjects, linkedNotes = linkedNotes)
    }

    /** The next start of the weekly slot strictly after [now]. */
    fun nextSlot(settings: ObjectiveSettings, now: LocalDateTime): LocalDateTime {
        val start = runCatching { LocalTime.parse(settings.slotStart) }.getOrDefault(LocalTime.of(14, 0))
        val day = DayOfWeek.of(settings.slotDay.coerceIn(1, 7))
        val candidate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).atTime(start)
        return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
    }
}
