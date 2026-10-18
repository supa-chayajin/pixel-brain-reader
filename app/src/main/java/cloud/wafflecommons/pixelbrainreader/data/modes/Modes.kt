package cloud.wafflecommons.pixelbrainreader.data.modes

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** What the phone is for right now. */
@Serializable
enum class ContextMode { WORK, PERSONAL, OFF }

/** A leave: the mode is Off for every day from [start] to [end] (ISO dates, inclusive). */
@Serializable
data class LeavePeriod(val start: String, val end: String, val label: String = "") {
    fun contains(date: LocalDate): Boolean = runCatching {
        !date.isBefore(LocalDate.parse(start)) && !date.isAfter(LocalDate.parse(end))
    }.getOrDefault(false)
}

/** A date worth a countdown (a deadline, a leave, an event). Work dates hide outside Work mode. */
@Serializable
data class KeyDate(val date: String, val label: String, val work: Boolean = true) {
    fun localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
}

/**
 * The modes configuration, kept in the vault (`10_Journal/system/context_modes.json`) so it syncs
 * and survives a reinstall. Every field has a default: older or partial files still load.
 */
@Serializable
data class ModesConfig(
    /** Vault folders that hold work content (hidden outside Work mode). */
    val workFolders: List<String> = emptyList(),
    val scheduleEnabled: Boolean = false,
    /** ISO days of week (1 = Monday). */
    val workDays: List<Int> = listOf(1, 2, 3, 4, 5),
    val workStart: String = "09:00",
    val workEnd: String = "18:00",
    val outsideHoursMode: ContextMode = ContextMode.OFF,
    val leavePeriods: List<LeavePeriod> = emptyList(),
    val keyDates: List<KeyDate> = emptyList()
) {
    fun startTime(): LocalTime = parseTime(workStart, LocalTime.of(9, 0))
    fun endTime(): LocalTime = parseTime(workEnd, LocalTime.of(18, 0))

    private fun parseTime(raw: String, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(raw) }.getOrDefault(fallback)
}

/** A manual choice. It lasts until [untilMillis] (the next schedule change or midnight). */
data class ModeOverride(val mode: ContextMode, val untilMillis: Long)

/** Why the mode is what it is (shown under the mode chip). */
enum class ModeSource { MANUAL, LEAVE, SCHEDULE, DEFAULT }

data class ResolvedMode(val mode: ContextMode, val source: ModeSource, val leave: LeavePeriod? = null)

/**
 * What each surface may show. One policy for the file list, search, AI search, resurfaced notes,
 * widgets and notifications — so "Personal" means the same thing everywhere.
 */
data class Visibility(
    val mode: ContextMode = ContextMode.WORK,
    val source: ModeSource = ModeSource.DEFAULT,
    val screenShareSafe: Boolean = false,
    val workFolders: List<String> = emptyList(),
    val leave: LeavePeriod? = null
) {
    /** Work is out of the picture: any mode but Work, or the screen is being shared. */
    val workContextHidden: Boolean get() = mode != ContextMode.WORK || screenShareSafe
    /** Work folders are hidden outside Work mode, and always while sharing the screen. */
    val hideWork: Boolean get() = workContextHidden && workFolders.isNotEmpty()
    val hidePrivate: Boolean get() = screenShareSafe
    val hideHealth: Boolean get() = screenShareSafe
    /** Work notifications (meeting prep, follow-ups, objective nudges) only in Work mode. */
    val muteWorkNotifications: Boolean get() = mode != ContextMode.WORK

    fun isWorkPath(path: String): Boolean = workFolders.any { folder -> path == folder || path.startsWith("$folder/") }

    fun isVisible(path: String): Boolean {
        if (hidePrivate && (path == PRIVATE_FOLDER || path.startsWith("$PRIVATE_FOLDER/"))) return false
        if (hideWork && isWorkPath(path)) return false
        return true
    }

    companion object {
        const val PRIVATE_FOLDER = "99_Private"
        val ALL = Visibility()
    }
}

/** The mode rules: a manual choice beats a leave, which beats the schedule. */
object ModeResolver {

    fun resolve(config: ModesConfig, override: ModeOverride?, now: LocalDateTime, zone: ZoneId): ResolvedMode {
        val leave = activeLeave(config, now.toLocalDate())
        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        if (override != null && override.untilMillis > nowMillis) {
            return ResolvedMode(override.mode, ModeSource.MANUAL, leave)
        }
        if (leave != null) return ResolvedMode(ContextMode.OFF, ModeSource.LEAVE, leave)
        if (!config.scheduleEnabled) return ResolvedMode(ContextMode.WORK, ModeSource.DEFAULT)
        val mode = if (inWorkHours(config, now)) ContextMode.WORK else config.outsideHoursMode
        return ResolvedMode(mode, ModeSource.SCHEDULE)
    }

    fun activeLeave(config: ModesConfig, date: LocalDate): LeavePeriod? = config.leavePeriods.firstOrNull { it.contains(date) }

    fun inWorkHours(config: ModesConfig, now: LocalDateTime): Boolean {
        if (now.dayOfWeek.value !in config.workDays) return false
        val time = now.toLocalTime()
        return !time.isBefore(config.startTime()) && time.isBefore(config.endTime())
    }

    /** The next moment the schedule changes the mode, or null without a schedule. */
    fun nextBoundary(config: ModesConfig, now: LocalDateTime): LocalDateTime? {
        if (!config.scheduleEnabled || config.workDays.isEmpty()) return null
        for (offset in 0..7L) {
            val day = now.toLocalDate().plusDays(offset)
            if (DayOfWeek.from(day).value !in config.workDays) continue
            listOf(day.atTime(config.startTime()), day.atTime(config.endTime()))
                .firstOrNull { it.isAfter(now) }
                ?.let { return it }
        }
        return null
    }

    /** A manual choice ends at the next schedule change, and at midnight at the latest. */
    fun overrideUntil(config: ModesConfig, now: LocalDateTime): LocalDateTime {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val boundary = nextBoundary(config, now)
        return if (boundary != null && boundary.isBefore(midnight)) boundary else midnight
    }

    /** Upcoming key dates (today included), nearest first; work dates only when they are visible. */
    fun upcomingKeyDates(config: ModesConfig, today: LocalDate, visibility: Visibility, limit: Int = 3): List<Pair<KeyDate, Long>> =
        config.keyDates
            .filter { !it.work || !visibility.workContextHidden }
            .mapNotNull { key -> key.localDate()?.let { date -> key to java.time.temporal.ChronoUnit.DAYS.between(today, date) } }
            .filter { (_, days) -> days >= 0 }
            .sortedBy { (_, days) -> days }
            .take(limit)
}
