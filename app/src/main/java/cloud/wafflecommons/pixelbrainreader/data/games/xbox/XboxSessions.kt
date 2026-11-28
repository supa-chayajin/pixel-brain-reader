package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.HH_MM
import cloud.wafflecommons.pixelbrainreader.data.games.SessionDiff
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Xbox's times (pure). The services write UTC times with 0 to 7 fractional digits
 * (`…12.4411936Z`, `…20.61Z`), most with a `Z`, some without; achievements use placeholders for
 * "no date": 0001-01-01 while locked, 1753-01-01 for a 360 achievement unlocked offline,
 * 2002-11-15 in a 360 title's full list.
 */
object XboxTimes {

    /** Before this year a date is one of Xbox's placeholders, never a real unlock. */
    private const val FIRST_REAL_YEAR = 2005

    /** Xbox's UTC times (0–7 fractional digits, with or without `Z`); null when unreadable. */
    fun instant(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cut = text.indexOfFirst { it == 'T' || it == 't' }
        val time = if (cut < 0) "" else text.substring(cut + 1)
        // A time without a zone is UTC: the services drop the Z now and then.
        val zoned = time.endsWith('Z') || time.endsWith('z') || time.contains('+') || time.contains('-')
        val iso = if (cut >= 0 && !zoned) text + "Z" else text
        return runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    }

    /** A real unlock time; null for Xbox's placeholders (any year before 2005). */
    fun realUnlock(raw: String?): Instant? =
        instant(raw)?.takeIf { it.atOffset(ZoneOffset.UTC).year >= FIRST_REAL_YEAR }
}

/**
 * Sessions from two Xbox syncs (pure, XB-D): a title's cumulative `MinutesPlayed` now, minus what
 * the previous sync saw, is a session. The first sync only records the baseline. Unknown minutes
 * are never 0: a title whose minutes become known is a baseline too, never one giant session. A
 * title the history lists for the first time (Xbox gives no first-played date) counts whole only
 * when it can only be new: the previous list was complete, it was played after the previous sync,
 * and its minutes fit in the time between the two syncs. 13.4: such a newcomer seen once without its
 * minutes ([cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState.newcomerSince]) gets the
 * same rule one sync late, from that time; and play between two syncs more than
 * [SessionDiff.UNDATED_GAP_SECONDS] apart is kept undated (G1). A count that went down is ignored.
 */
object XboxSessionDiff {

    /** Xbox counts whole minutes: one is the least a session can be. */
    const val MIN_MINUTES = 1L

    /**
     * [lastPlayedIsStart] says which end of the latest session titlehub's `lastTimePlayed` marks;
     * the session's start is that time, or that time minus the minutes added since the last sync.
     */
    fun sessions(
        previous: XboxSnapshot?,
        current: XboxSnapshot,
        zone: ZoneId,
        lastPlayedIsStart: Boolean
    ): List<GameSessionRecord> {
        if (previous == null || previous.syncedAt.isBlank()) return emptyList()
        val previousSync = XboxTimes.instant(previous.syncedAt) ?: return emptyList()
        val currentSync = XboxTimes.instant(current.syncedAt) ?: return emptyList()
        val gapSeconds = Duration.between(previousSync, currentSync).seconds
        val before = previous.titles.associateBy { it.titleId }
        return current.titles.mapNotNull { title ->
            val minutes = title.minutesPlayed ?: return@mapNotNull null
            val old = before[title.titleId]
            val oldMinutes = old?.minutesPlayed
            val lastPlayed = XboxTimes.instant(title.lastPlayed)
            val newcomerSince = old?.newcomerSince?.let(XboxTimes::instant)
            // Where the play since may have begun: the previous sync, or a newcomer's first sighting.
            var since = previousSync
            val added = when {
                oldMinutes != null -> minutes - oldMinutes
                // A newcomer whose minutes weren't read the first time: the newcomer rule, from then.
                old != null && newcomerSince != null -> {
                    since = newcomerSince
                    val fits = minutes <= (Duration.between(newcomerSince, currentSync).seconds + 60) / 60
                    if ((lastPlayed ?: Instant.EPOCH) > newcomerSince && fits) minutes else 0L
                }
                // Known now, unknown before: a baseline.
                old != null -> 0L
                // Newcomers: only when nothing older could have been cut off the last list,
                !previous.complete -> 0L
                // it was played since the last sync,
                (lastPlayed ?: Instant.EPOCH) <= previousSync -> 0L
                // and its minutes fit between the two syncs (with a minute's slack), else older play is in them.
                minutes > (gapSeconds + 60) / 60 -> 0L
                else -> minutes
            }
            if (added < MIN_MINUTES) return@mapNotNull null
            val last = lastPlayed ?: currentSync
            // Keyed on the PREVIOUS sync: a run whose snapshot failed to save is replayed from the
            // same baseline, and its session replaces this one instead of adding a second.
            val id = "xbox:${title.titleId}:${previous.syncedAt}"
            val length = added.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // G1: no console says which day play spread over more than 30 hours was.
            if (Duration.between(since, currentSync).seconds > SessionDiff.UNDATED_GAP_SECONDS) {
                val day = runCatching { last.atZone(zone).toLocalDate() }.getOrNull() ?: return@mapNotNull null
                return@mapNotNull GameSessionRecord(
                    id = id, date = day.toString(), start = null, minutes = length,
                    source = GameSessionRecord.SOURCE_XBOX, titleId = title.titleId, since = since.toString()
                )
            }
            // A count no clock can hold (garbage from the service) gives no session rather than an error.
            val start = runCatching {
                (if (lastPlayedIsStart) last else last.minusSeconds(Math.multiplyExact(added, 60L))).atZone(zone).toLocalDateTime()
            }.getOrNull() ?: return@mapNotNull null
            GameSessionRecord(
                id = id,
                date = start.toLocalDate().toString(),
                start = start.toLocalTime().format(HH_MM),
                minutes = length,
                source = GameSessionRecord.SOURCE_XBOX,
                titleId = title.titleId
            )
        }
    }
}
