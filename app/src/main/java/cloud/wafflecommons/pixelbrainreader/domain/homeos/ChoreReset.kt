package cloud.wafflecommons.pixelbrainreader.domain.homeos

import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The 15-minute reset (E16, 12.20; the owner's D13): the due chores that fit the minutes, the most
 * overdue first, and the due ones whose time isn't said yet. Pure.
 */
object ChoreReset {

    const val BUDGET_MINUTES = 15

    data class Plan(val picks: List<ChoreEntity>, val minutes: Int, val withoutMinutes: List<ChoreEntity>) {
        companion object {
            val EMPTY = Plan(emptyList(), 0, emptyList())
        }
    }

    fun plan(chores: List<ChoreEntity>, today: LocalDate, budget: Int = BUDGET_MINUTES): Plan {
        val due = chores
            .filter { !it.archived && overdue(it, today) >= 1.0 }
            .sortedWith(compareByDescending<ChoreEntity> { overdue(it, today) }.thenBy { it.name })
        val picks = mutableListOf<ChoreEntity>()
        var used = 0
        for (chore in due) {
            if (chore.minutes <= 0 || used + chore.minutes > budget) continue
            picks += chore
            used += chore.minutes
        }
        return Plan(picks, used, due.filter { it.minutes <= 0 })
    }

    /** Days since it was done over its frequency: 1 or more is due; a chore never done is due first. */
    fun overdue(chore: ChoreEntity, today: LocalDate): Double {
        val last = runCatching { LocalDate.parse(chore.lastDoneDate) }.getOrNull() ?: return Double.MAX_VALUE
        return ChronoUnit.DAYS.between(last, today).toDouble() / chore.frequencyDays.coerceAtLeast(1)
    }
}
