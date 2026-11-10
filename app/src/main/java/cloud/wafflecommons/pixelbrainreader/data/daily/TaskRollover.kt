package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import java.time.LocalDate

/** One unfinished task of an earlier day, offered on today's board. */
data class RolloverTask(val label: String, val priority: Int, val key: String)

/** What Daily offers on [to]: the unfinished tasks of [from], the most recent earlier day with a note. */
data class RolloverOffer(val from: LocalDate, val to: LocalDate, val tasks: List<RolloverTask>)

/**
 * Task rollover (12.11.0, D1): an earlier day's unfinished tasks are offered on today's board, never
 * moved. Bring adds one to today, Dismiss forgets it; both are remembered on the phone under [key],
 * and the earlier day's note is never rewritten, so the burn format doesn't change (owner, 2026-11-10).
 *
 * Google tasks are left out (the Google import keeps them), and so is a task today's board already
 * holds, brought or typed again.
 */
object TaskRollover {

    /** How far back the most recent earlier day with a note is looked for. */
    const val LOOKBACK_DAYS = 7L

    private val SPACES = Regex("[ \\t]+")

    fun normalize(label: String): String = label.trim().lowercase().replace(SPACES, " ")

    /** A task handled on this phone (brought or dismissed) is remembered under this key. */
    fun key(from: LocalDate, label: String): String = "$from|${normalize(label)}"

    /** The offer for [to] from [from]'s tasks, or null when nothing is left to offer. */
    fun offer(
        from: LocalDate,
        to: LocalDate,
        earlierTasks: List<DailyTaskEntity>,
        todayTasks: List<DailyTaskEntity>,
        handled: Set<String>
    ): RolloverOffer? {
        val onToday = todayTasks.mapTo(HashSet()) { normalize(it.label) }
        val tasks = earlierTasks
            .asSequence()
            .filter { !it.isDone && it.googleTaskId == null && it.label.isNotBlank() }
            .filter { normalize(it.label) !in onToday }
            .map { RolloverTask(label = it.label.trim(), priority = it.priority, key = key(from, it.label)) }
            .filter { it.key !in handled }
            .distinctBy { it.key }
            .toList()
        return if (tasks.isEmpty()) null else RolloverOffer(from, to, tasks)
    }

    /** The handled keys still worth keeping on [today]: an older day is never looked at again. */
    fun prune(handled: Set<String>, today: LocalDate): Set<String> {
        val oldest = today.minusDays(LOOKBACK_DAYS)
        return handled.filterTo(HashSet()) { key ->
            val day = runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()
            day != null && !day.isBefore(oldest)
        }
    }
}
