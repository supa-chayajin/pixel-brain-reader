package cloud.wafflecommons.pixelbrainreader.data.games

import java.security.MessageDigest
import java.time.Duration
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
 * just beyond the last page). A play time that went down is ignored. Two syncs more than
 * [UNDATED_GAP_SECONDS] apart (13.4, G1: the owner's answer) can't say which day the play was: the
 * record keeps its minutes for the hours, has no start and carries [GameSessionRecord.since].
 */
object SessionDiff {

    /** Play under a minute is noise (a launch, an update screen). */
    const val MIN_SECONDS = 60L

    /** G1: more than 30 hours between two syncs, and the play since has no day of its own. */
    const val UNDATED_GAP_SECONDS = 30 * 3600L

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
        val currentSync = instant(current.syncedAt)
        // G1: an unreadable sync time keeps the records dated, as before 13.4.
        val undated = currentSync != null && Duration.between(previousSync, currentSync).seconds > UNDATED_GAP_SECONDS
        return current.titles.mapNotNull { title ->
            val old = before[title.titleId]
            val added = when {
                old != null -> title.playSeconds - old.playSeconds
                (instant(title.firstPlayed) ?: Instant.EPOCH) > previousSync -> title.playSeconds
                else -> 0L
            }
            if (added < MIN_SECONDS) return@mapNotNull null
            val last = instant(title.lastPlayed) ?: currentSync ?: return@mapNotNull null
            // Keyed on the PREVIOUS sync: a run whose snapshot failed to save is replayed from the
            // same baseline, and its session replaces this one instead of adding a second.
            val id = "psn:${title.titleId}:${previous.syncedAt}"
            val minutes = ((added + 30) / 60).toInt()
            if (undated) {
                val day = runCatching { last.atZone(zone).toLocalDate() }.getOrNull() ?: return@mapNotNull null
                return@mapNotNull GameSessionRecord(
                    id = id, date = day.toString(), start = null, minutes = minutes,
                    source = GameSessionRecord.SOURCE_PSN, titleId = title.titleId, since = previousSync.toString()
                )
            }
            val start = (if (lastPlayedIsStart) last else last.minusSeconds(added)).atZone(zone).toLocalDateTime()
            GameSessionRecord(
                id = id,
                date = start.toLocalDate().toString(),
                start = start.toLocalTime().format(HH_MM),
                minutes = minutes,
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
 * one (the owner pressed it), else the earliest a console measured; the length is what the consoles
 * measured when they measured anything — PSN's and Xbox's add up, the same game played on both the
 * same day being one line (XB5) — else the manual sessions'. Sessions whose page is unknown are
 * left out, and so are the undated ones (13.4, G1: [GameSessionRecord.since]): no Timeline line ever
 * shows play no console placed on a day.
 */
object GameDays {

    fun lines(date: LocalDate, sessions: List<GameSessionRecord>, pageOf: (GameSessionRecord) -> GamePage?): List<GameDayLine> {
        val day = date.toString()
        return sessions.filter { it.date == day && it.since == null }
            .mapNotNull { record -> pageOf(record)?.let { it to record } }
            .groupBy({ it.first.path }, { it })
            .mapNotNull { (_, pairs) ->
                val page = pairs.first().first
                val records = pairs.map { it.second }
                val manual = records.filter { it.source == GameSessionRecord.SOURCE_MANUAL }
                val measured = records.filter { it.measured }
                val start = (manual.mapNotNull { time(it.start) }.minOrNull() ?: measured.mapNotNull { time(it.start) }.minOrNull())
                    ?: return@mapNotNull null
                val minutes = if (measured.isNotEmpty()) measured.sumOf { it.minutes } else manual.sumOf { it.minutes }
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
 * A game's play time (pure, XB3): what the consoles measured once either measures the game,
 * PSN's and Xbox's totals added up; else the time typed from the console (`played_before`, 13.1)
 * plus the sessions recorded since; null when there is neither.
 */
object GameHours {
    /**
     * A game's play time: what the consoles measured — PSN's seconds plus Xbox's minutes — once
     * either measures the game; else `played_before` plus the sessions since; null when none.
     */
    fun of(psnSeconds: Long, playedBefore: Double?, manualMinutes: Int, xboxMinutes: Long = 0L): Double? {
        val psn = psnSeconds.coerceAtLeast(0L)
        val xbox = xboxMinutes.coerceAtLeast(0L)
        return when {
            psn > 0 || xbox > 0 -> psn / 3600.0 + xbox / 60.0
            playedBefore == null && manualMinutes <= 0 -> null
            else -> (playedBefore ?: 0.0) + manualMinutes.coerceAtLeast(0) / 60.0
        }
    }
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
            id = ManualSessions.idFor(page, startMillis),
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

/**
 * A manual session typed or fixed by hand (13.1, pure): the record a started-and-stopped session
 * would have written, keyed the same way (13.4: `manual:<start millis>:<page tag>`, the page in the
 * key so two games' sessions of one minute never share an id). Typing a session twice replaces it,
 * because the merge ([GameSessionMerge]) matches the page and the minute, a phone-started one
 * included. A typed start is cut to the minute; a started one keeps its milliseconds. Older ids
 * (`manual:<start millis>`) stay as they are: edits and deletes find a record by the id read.
 */
object ManualSessions {

    /** Longest session that can be typed: a day. */
    const val MAX_MINUTES = 24 * 60

    private const val HEX = "0123456789abcdef"

    /** The first 8 lowercase hex characters of the SHA-256 of [page]'s path (UTF-8). */
    fun pageTag(page: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(page.toByteArray(Charsets.UTF_8))
        return buildString {
            digest.take(4).forEach { byte ->
                val b = byte.toInt() and 0xff
                append(HEX[b shr 4])
                append(HEX[b and 0x0f])
            }
        }
    }

    /** A manual session's id (13.4): `manual:<start millis>:<page tag>`. */
    fun idFor(page: String, startMillis: Long): String = "manual:$startMillis:${pageTag(page)}"

    fun record(page: String, date: LocalDate, start: LocalTime, minutes: Int, zone: ZoneId): GameSessionRecord? {
        if (minutes !in 1..MAX_MINUTES) return null
        val startMillis = date.atTime(start.withSecond(0).withNano(0)).atZone(zone).toInstant().toEpochMilli()
        return GameSessionRecord(
            id = idFor(page, startMillis),
            date = date.toString(),
            start = start.format(HH_MM),
            minutes = minutes,
            source = GameSessionRecord.SOURCE_MANUAL,
            page = page
        )
    }

    /** [old] with a new start and length, same day and page; null when the length is out of range. */
    fun edited(old: GameSessionRecord, start: LocalTime, minutes: Int, zone: ZoneId): GameSessionRecord? {
        val page = old.page ?: return null
        val date = runCatching { LocalDate.parse(old.date) }.getOrNull() ?: return null
        return record(page, date, start, minutes, zone)
    }
}

/**
 * How a session joins its month (13.4, pure). A record replaces another with its id, except that a
 * manual one does so only on its own page; a manual record also replaces a manual record of the same
 * page, day and start minute, whatever its id (typing a session twice, a phone-started one
 * included). Nothing else is replaced: two games' sessions of one minute are both kept.
 */
object GameSessionMerge {

    data class Merged(val sessions: List<GameSessionRecord>, val replacedDates: Set<String>)

    /** A month's order: by day, start (none first), id. */
    val ORDER: Comparator<GameSessionRecord> = compareBy({ it.date }, { it.start ?: "" }, { it.id })

    /** [existing] with each of [incoming] in its place, and the days the records it replaced were on (when another day). */
    fun merge(existing: List<GameSessionRecord>, incoming: List<GameSessionRecord>): Merged {
        val out = existing.toMutableList()
        val dates = mutableSetOf<String>()
        incoming.forEach { record ->
            out.filter { replaces(record, it) }.forEach { old -> if (old.date != record.date) dates += old.date }
            out.removeAll { replaces(record, it) }
            out += record
        }
        return Merged(out.sortedWith(ORDER), dates)
    }

    fun replaces(record: GameSessionRecord, old: GameSessionRecord): Boolean {
        val manual = record.source == GameSessionRecord.SOURCE_MANUAL
        if (record.id == old.id && (!manual || record.page == old.page)) return true
        return manual && old.source == GameSessionRecord.SOURCE_MANUAL && record.page != null && record.page == old.page &&
            record.date == old.date && record.start != null && record.start == old.start
    }

    /** The previous sync's instant a console id carries (`psn:<title>:<instant>`, `xbox:<title>:<instant>`); null for any other id. */
    fun baselineOf(id: String): Instant? {
        val parts = id.split(':', limit = 3)
        if (parts.size != 3 || (parts[0] != "psn" && parts[0] != "xbox")) return null
        return runCatching { Instant.parse(parts[2]) }.getOrNull()
    }
}

/** Manual sessions whose page is gone (renamed or deleted on the desktop), for the fold's "Link to a page". */
data class OrphanedPage(val oldPath: String, val name: String, val minutes: Int, val sessions: Int, val lastDate: LocalDate?)

/**
 * The typed and phone-started sessions no page claims any more (13.4, pure): their path is no
 * page's, nor the old path of a page moved elsewhere under the same file name ([movedTo], what the
 * repository's resolver follows too).
 */
object OrphanedSessions {

    /** None while [pages] is empty (a mirror still filling); a path that resolves by its unique file name (a move) isn't orphaned. */
    fun of(sessions: List<GameSessionRecord>, pages: List<GamePage>): List<OrphanedPage> {
        if (pages.isEmpty()) return emptyList()
        val paths = pages.mapTo(HashSet()) { it.path }
        return sessions.asSequence()
            .filter { it.source == GameSessionRecord.SOURCE_MANUAL }
            .mapNotNull { record -> record.page?.let { it to record } }
            .filter { (path, _) -> path !in paths && movedTo(path, pages) == null }
            .groupBy({ it.first }, { it.second })
            .map { (path, records) ->
                OrphanedPage(
                    oldPath = path,
                    name = keyOfPath(path).replace('_', ' '),
                    minutes = records.sumOf { it.minutes },
                    sessions = records.size,
                    lastDate = records.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.maxOrNull()
                )
            }
            .sortedWith(compareBy<OrphanedPage, LocalDate?>(nullsLast(reverseOrder())) { it.lastDate }.thenBy { it.name.lowercase() }.thenBy { it.oldPath })
    }

    /** The one page whose file name is [oldPath]'s (a page moved to another folder); null when none or several. */
    internal fun movedTo(oldPath: String, pages: List<GamePage>): GamePage? {
        val key = keyOfPath(oldPath)
        return pages.filter { it.key == key }.singleOrNull()
    }

    /** A page path's file name without `.md`: what [GamePage.key] is. */
    internal fun keyOfPath(path: String): String = path.substringAfterLast('/').removeSuffix(".md")
}

/** The game played last (13.4, pure): the session picker's default, the tile's game, the tab's mark. */
object LastPlayed {

    /**
     * The game played last: today's GameSessionController.defaultPage rules over data already loaded
     * (the latest of [sessions] and both consoles' last-played times, else the first game being
     * played, else the first page). A record without a start (an undated one) counts at midnight of
     * its date. Null without pages.
     */
    fun page(
        pages: List<GamePage>,
        links: GameLinks,
        sessions: List<GameSessionRecord>,
        psn: PsnSnapshot?,
        xbox: XboxSnapshot?,
        zone: ZoneId,
        resolve: (GameSessionRecord) -> GamePage?
    ): GamePage? {
        if (pages.isEmpty()) return null
        val byPath = pages.associateBy { it.path }
        val fromSessions = sessions.mapNotNull { record ->
            val page = resolve(record) ?: return@mapNotNull null
            val day = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: return@mapNotNull null
            val time = record.start?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.MIDNIGHT
            page to day.atTime(time)
        }
        val fromPsn = psn?.titles.orEmpty().mapNotNull { title ->
            val page = links.pageOf(title.titleId)?.let(byPath::get) ?: return@mapNotNull null
            val at = title.lastPlayed?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDateTime() }.getOrNull() } ?: return@mapNotNull null
            page to at
        }
        val fromXbox = xbox?.titles.orEmpty().mapNotNull { title ->
            val page = links.consolePage(GameConsole.XBOX, title.titleId)?.let(byPath::get) ?: return@mapNotNull null
            val at = title.lastPlayed?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDateTime() }.getOrNull() } ?: return@mapNotNull null
            page to at
        }
        return (fromSessions + fromPsn + fromXbox).maxByOrNull { it.second }?.first
            ?: pages.firstOrNull { it.state == GameState.PLAYING } ?: pages.first()
    }
}

/**
 * The running session's "Still playing?" state (13.1), kept on the phone with the session it
 * belongs to: [nextAskAt] is when the question is due (3 h after the start, or 1 h after Keep
 * playing), [askedAt] when it was asked and is still unanswered.
 */
data class SessionCheck(val sessionStart: Long, val nextAskAt: Long, val askedAt: Long? = null) {

    fun encode(): String = "$sessionStart|$nextAskAt|${askedAt ?: ""}"

    companion object {
        fun first(session: RunningGame): SessionCheck =
            SessionCheck(session.startMillis, session.startMillis + GameSessionGuard.ASK_AFTER_MS)

        fun decode(raw: String?): SessionCheck? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val start = parts[0].toLongOrNull() ?: return null
            val next = parts[1].toLongOrNull() ?: return null
            return SessionCheck(start, next, parts[2].toLongOrNull())
        }
    }
}

/**
 * A session nobody stopped (the owner's call, 2026-11-16, pure): 3 h after its start the phone asks
 * "Still playing?"; Keep playing asks again an hour later; with no answer an hour after the
 * question, the session ends at the time the question was due — not when someone notices.
 */
object GameSessionGuard {

    const val ASK_AFTER_MS = 3 * 3_600_000L
    const val ANSWER_MS = 3_600_000L
    const val KEEP_MS = 3_600_000L

    sealed interface Step {
        /** Nothing to do before [at]. */
        data class Wait(val at: Long) : Step

        /** Ask now; the answer is awaited until [answerBy]. */
        data class Ask(val answerBy: Long) : Step

        /** End the session, recorded as stopping at [at]. */
        data class End(val at: Long) : Step
    }

    fun step(check: SessionCheck, now: Long): Step {
        val asked = check.askedAt
        return when {
            asked != null && now >= asked + ANSWER_MS -> Step.End(minOf(check.nextAskAt, asked).coerceAtLeast(check.sessionStart))
            asked != null -> Step.Wait(asked + ANSWER_MS)
            now >= check.nextAskAt -> Step.Ask(now + ANSWER_MS)
            else -> Step.Wait(check.nextAskAt)
        }
    }

    /** The answer "Keep playing": the next question an hour from now. */
    fun keep(check: SessionCheck, now: Long): SessionCheck = check.copy(nextAskAt = now + KEEP_MS, askedAt = null)
}

internal val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
