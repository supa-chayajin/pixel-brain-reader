package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The shutdown ritual (D8, owner 2026-11-11), pure: one Daily banner from [LEAD_MINUTES] before
 * work ends on a work day (Modes' days and hours, whether or not the schedule switches modes) until
 * the day is closed. Unfinished tasks go to tomorrow, to the someday note or nowhere; the rollover
 * (D1) leaves each one alone through its handled key.
 */
object Shutdown {

    const val LEAD_MINUTES = 30L
    const val SOMEDAY_PATH = "00_Inbox/Un jour.md"
    const val SOMEDAY_TITLE = "Un jour"
    const val WIN_TAG = "victoire"

    /** True from [LEAD_MINUTES] before work ends on a work day until midnight, unless the day was closed. */
    fun isDue(config: ModesConfig, now: LocalDateTime, closedDay: LocalDate?): Boolean {
        val today = now.toLocalDate()
        if (closedDay == today) return false
        if (DayOfWeek.from(today).value !in config.workDays) return false
        return !now.isBefore(startOf(config, today))
    }

    /** When the banner starts on [day]. */
    fun startOf(config: ModesConfig, day: LocalDate): LocalDateTime = day.atTime(config.endTime()).minusMinutes(LEAD_MINUTES)

    /** Today's open local tasks the owner hasn't handled yet; Google tasks stay with the Google import (D1). */
    fun openTasks(tasks: List<DailyTaskEntity>, today: LocalDate, handled: Set<String>): List<DailyTaskEntity> =
        tasks.filter { !it.isDone && it.googleTaskId == null && TaskRollover.key(today, it.label) !in handled }

    /** The key under which tomorrow's rollover leaves [label] alone. */
    fun handledKey(today: LocalDate, label: String): String = TaskRollover.key(today, label)

    /**
     * The someday note with [label] added as an open line dated [day]; [current] is the note on
     * disk (null: a new note). Only ever appended, never rewritten: an open line with the same task
     * is kept as it is.
     */
    fun withSomeday(current: String?, label: String, day: LocalDate): String {
        val text = label.replace('\n', ' ').replace('\r', ' ').trim()
        val line = "- [ ] $text ($day)"
        if (current == null) {
            return VaultSchema.frontmatter(title = SOMEDAY_TITLE, path = SOMEDAY_PATH, date = day, tags = listOf("un-jour")) +
                "\n# $SOMEDAY_TITLE\n\nLes tâches mises de côté à la fin d'une journée de travail, avec le jour où elles l'ont été.\n\n" +
                line + "\n"
        }
        val already = current.lines().any { existing ->
            val open = existing.trim().removePrefix("- [ ] ")
            existing.trim().startsWith("- [ ] ") &&
                TaskRollover.normalize(open.substringBeforeLast(" (")) == TaskRollover.normalize(text)
        }
        if (already) return current
        return current.trimEnd('\n') + "\n" + line + "\n"
    }

    /** The inbox's notes, the someday note aside (it lives there for good). */
    fun inboxCount(paths: List<String>): Int = paths.count { it.endsWith(".md") && it != SOMEDAY_PATH }
}
