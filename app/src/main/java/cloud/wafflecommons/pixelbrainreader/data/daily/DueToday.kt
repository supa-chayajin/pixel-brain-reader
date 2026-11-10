package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.MeetingFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveFormat
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import java.time.LocalDate

enum class DueKind { MILESTONE, ACTION, CHORE }

/**
 * One line of the "Due today" block: [due] is today or earlier. [path] is the note to open (null for
 * a chore), [context] what it belongs to (the objective, the note, the room).
 */
data class DueItem(
    val kind: DueKind,
    val text: String,
    val due: LocalDate,
    val context: String,
    val path: String? = null
) {
    fun overdueDays(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(due, today)
}

/**
 * The "Due today" block (12.11.0, D2): what is due today or overdue, from what is already in the
 * vault and in Room — objective milestones, your `@moi` actions, the chores at or past their
 * frequency. Read only. Overdue first (oldest first), then today's.
 */
object DueToday {

    /** Open milestones of objective notes and open `@moi` lines, due on [today] or earlier. One per line. */
    fun fromNotes(notes: List<WorkNote>, today: LocalDate): List<DueItem> {
        val items = mutableListOf<DueItem>()
        val seen = HashSet<Pair<String, String>>()
        notes.asSequence()
            // Desktop mirrors and worklogs are never parsed (#15); daily notes are the board's tasks.
            .filter { !VaultSchema.isDailyNote(it.path) && !MirrorNotes.isGenerated(it.path, it.content) }
            .forEach { note ->
                val actions = MeetingFormat.actions(note.path, note.content)
                    .filter { it.mine && !it.done && it.due != null && !it.due.isAfter(today) }
                val actionText = actions.associate { it.sourceLine to it.text }
                if (ObjectiveFormat.isObjective(note)) {
                    ObjectiveFormat.milestones(note.content)
                        .filter { !it.done && it.due != null && !it.due.isAfter(today) }
                        .forEach { m ->
                            // An "@moi" milestone reads as its action, without the handle.
                            val text = actionText[m.sourceLine] ?: m.text
                            if (seen.add(note.path to m.sourceLine)) items += DueItem(DueKind.MILESTONE, text, m.due!!, note.title, note.path)
                        }
                }
                actions.forEach { a ->
                    if (seen.add(note.path to a.sourceLine)) items += DueItem(DueKind.ACTION, a.text, a.due!!, note.title, note.path)
                }
            }
        return items
    }

    /** The chores at or past their frequency, as the Chores screen counts it (never done = due today). */
    fun fromChores(chores: List<ChoreEntity>, roomNames: Map<String, String>, today: LocalDate): List<DueItem> =
        chores.filter { !it.archived }.mapNotNull { chore ->
            val frequency = chore.frequencyDays.takeIf { it > 0 } ?: 1
            val due = runCatching { LocalDate.parse(chore.lastDoneDate) }.getOrNull()
                ?.plusDays(frequency.toLong())
                ?: today
            if (due.isAfter(today)) null
            else DueItem(DueKind.CHORE, chore.name, due, roomNames[chore.roomId] ?: "Home")
        }

    /** The chores of the block as one line (owner, 2026-11-10): how many, their rooms by urgency, the oldest due date. */
    data class ChoreSummary(val count: Int, val rooms: List<String>, val oldestDue: LocalDate)

    /** Null when no chore is due. */
    fun choreSummary(items: List<DueItem>): ChoreSummary? {
        val chores = items.filter { it.kind == DueKind.CHORE }
        if (chores.isEmpty()) return null
        return ChoreSummary(chores.size, chores.sortedBy { it.due }.map { it.context }.distinct(), chores.minOf { it.due })
    }

    fun sorted(items: List<DueItem>): List<DueItem> =
        items.sortedWith(compareBy<DueItem> { it.due }.thenBy { it.kind.ordinal }.thenBy { it.text.lowercase() })
}
