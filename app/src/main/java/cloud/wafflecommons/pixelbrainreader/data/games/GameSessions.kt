package cloud.wafflecommons.pixelbrainreader.data.games

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PSN's play time, an ISO 8601 duration to the second (`PT228H56M33S`), as whole seconds. */
object PsnDuration {

    private val ISO = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.\\d+)?S)?)?$")

    fun seconds(raw: String?): Long? {
        val match = ISO.matchEntire(raw?.trim().orEmpty()) ?: return null
        if (raw?.trim() == "P" || raw?.trim() == "PT") return null
        val (d, h, m, s) = match.destructured
        fun n(x: String) = x.toLongOrNull() ?: 0L
        return n(d) * 86_400 + n(h) * 3_600 + n(m) * 60 + n(s)
    }
}

/**
 * Sessions from two PSN syncs (pure): a title's play time now, minus what the previous sync saw,
 * is a session (RFC-009 §4.3). The first sync only records the baseline. A title PSN lists for the
 * first time counts whole only when it was first played after the previous sync (otherwise it was
 * just beyond the last page). A play time that went down is ignored.
 */
object SessionDiff {

    /** Play under a minute is noise (a launch, an update screen). */
    const val MIN_SECONDS = 60L

    /**
     * [lastPlayedIsStart] says which end of the latest session PSN's `lastPlayedDateTime` marks;
     * the session's start is that time, or that time minus the play added since the last sync.
     */
    fun sessions(
        previous: PsnSnapshot?,
        current: PsnSnapshot,
        zone: ZoneId,
        lastPlayedIsStart: Boolean
    ): List<GameSessionRecord> {
        if (previous == null || previous.syncedAt.isBlank()) return emptyList()
        val before = previous.titles.associateBy { it.titleId }
        val previousSync = instant(previous.syncedAt) ?: return emptyList()
        return current.titles.mapNotNull { title ->
            val old = before[title.titleId]
            val added = when {
                old != null -> title.playSeconds - old.playSeconds
                (instant(title.firstPlayed) ?: Instant.EPOCH) > previousSync -> title.playSeconds
                else -> 0L
            }
            if (added < MIN_SECONDS) return@mapNotNull null
            val last = instant(title.lastPlayed) ?: instant(current.syncedAt) ?: return@mapNotNull null
            val start = (if (lastPlayedIsStart) last else last.minusSeconds(added)).atZone(zone).toLocalDateTime()
            GameSessionRecord(
                // Keyed on the PREVIOUS sync: a run whose snapshot failed to save is replayed from
                // the same baseline, and its session replaces this one instead of adding a second.
                id = "psn:${title.titleId}:${previous.syncedAt}",
                date = start.toLocalDate().toString(),
                start = start.toLocalTime().format(HH_MM),
                minutes = ((added + 30) / 60).toInt(),
                source = GameSessionRecord.SOURCE_PSN,
                titleId = title.titleId
            )
        }
    }

    private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** One Timeline line: a game's play on one day (RFC-009 §4.3). */
data class GameDayLine(val page: GamePage, val start: LocalTime, val minutes: Int)

/**
 * A day's game lines (pure): one per game. The start is the earliest manual start when there is
 * one (the owner pressed it), else PSN's; the length is what PSN measured when it measured
 * anything, else the manual sessions'. Sessions whose page is unknown are left out.
 */
object GameDays {

    fun lines(date: LocalDate, sessions: List<GameSessionRecord>, pageOf: (GameSessionRecord) -> GamePage?): List<GameDayLine> {
        val day = date.toString()
        return sessions.filter { it.date == day }
            .mapNotNull { record -> pageOf(record)?.let { it to record } }
            .groupBy({ it.first.path }, { it })
            .mapNotNull { (_, pairs) ->
                val page = pairs.first().first
                val records = pairs.map { it.second }
                val manual = records.filter { it.source == GameSessionRecord.SOURCE_MANUAL }
                val psn = records.filter { it.source == GameSessionRecord.SOURCE_PSN }
                val start = (manual.mapNotNull { time(it.start) }.minOrNull() ?: psn.mapNotNull { time(it.start) }.minOrNull())
                    ?: return@mapNotNull null
                val minutes = if (psn.isNotEmpty()) psn.sumOf { it.minutes } else manual.sumOf { it.minutes }
                if (minutes <= 0) null else GameDayLine(page, start, minutes)
            }
            .sortedWith(compareBy({ it.start }, { it.page.title.lowercase() }))
    }

    /** Minutes played per page over [sessions] (each day counted the way [lines] counts it). */
    fun totals(sessions: List<GameSessionRecord>, pageOf: (GameSessionRecord) -> GamePage?): Map<String, Int> =
        sessions.map { it.date }.distinct().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .flatMap { lines(it, sessions, pageOf) }
            .groupBy { it.page.path }
            .mapValues { (_, lines) -> lines.sumOf { it.minutes } }

    private fun time(raw: String?): LocalTime? = raw?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
}

/**
 * The session running on the phone (pure, RFC-009 §4.3): one at a time, kept as
 * `startMillis|page path` so it survives the app being closed.
 */
data class RunningGame(val startMillis: Long, val page: String) {

    fun encode(): String = "$startMillis|$page"

    fun minutes(now: Long): Int = ((now - startMillis).coerceAtLeast(0L) / 60_000L).toInt()

    /** The session as a record, once stopped at [now]; null when it lasted under a minute. */
    fun record(now: Long, zone: ZoneId): GameSessionRecord? {
        val minutes = minutes(now)
        if (minutes < 1) return null
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), zone)
        return GameSessionRecord(
            id = "manual:$startMillis",
            date = start.toLocalDate().toString(),
            start = start.toLocalTime().format(HH_MM),
            minutes = minutes,
            source = GameSessionRecord.SOURCE_MANUAL,
            page = page
        )
    }

    companion object {
        fun decode(raw: String?): RunningGame? {
            val cut = raw?.indexOf('|') ?: return null
            if (cut <= 0) return null
            val start = raw.substring(0, cut).toLongOrNull() ?: return null
            val page = raw.substring(cut + 1).takeIf { it.endsWith(".md") } ?: return null
            return RunningGame(start, page).takeIf { start > 0 }
        }
    }
}

internal val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
