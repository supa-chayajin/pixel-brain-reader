package cloud.wafflecommons.pixelbrainreader.data.games

import android.graphics.Bitmap
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.daily.Shutdown
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.di.AppScopeErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** When a game night guards a session (D7, D13), pure. */
object GameNight {

    /** In Perso, outside work hours: never in Off (custody evenings), never in Work or work hours. */
    fun guards(mode: ContextMode, inWorkHours: Boolean): Boolean = mode == ContextMode.PERSONAL && !inWorkHours

    /**
     * The day whose shutdown banner a game night started at [moment] keeps away (13.4): that day's,
     * once its banner has begun ([Shutdown.startOf]); null after midnight or before work, when the
     * day's banner is still hours away. D7 and D13 meant the evening's banner, hidden until morning,
     * never the next evening's.
     */
    fun dayToHide(config: ModesConfig, moment: LocalDateTime): LocalDate? =
        moment.toLocalDate().takeIf { !moment.isBefore(Shutdown.startOf(config, it)) }

    /** The shutdown banner stays away the rest of a day a game night started on. */
    fun hidesShutdown(gameNightDay: String?, today: LocalDate): Boolean = gameNightDay == today.toString()
}

/** What starting a session did about the game night. */
enum class GameNightOutcome {
    /** Not a Perso evening: no guard. */
    NONE,
    /** The "Game night" mode is on. */
    ON,
    /** It would be on, but the owner hasn't granted Do Not Disturb access yet. */
    NEEDS_ACCESS
}

/**
 * The manual game session (RFC-009 §4.3, D3): one at a time, kept on the phone, shown as a Live
 * Update; its stop writes a `manual` session. Starting one on a Perso evening starts a game night
 * (§4.5). Nothing on a read-only device.
 *
 * 13.4 (the audit's critical finding): [start], [stop], [check], [keepPlaying] and [restore] take
 * one lock, so none of them interleaves with another (a Stop landing mid-check can't leave the
 * notification posted again), and each runs to its end whatever cancels its caller: a tile or a page
 * going away, or the check's own job being replaced. A stop saves the session before it clears
 * anything and keeps it running when the save fails, so a later Stop or check saves it under the
 * same id; the game night ends with every stop; and the check's own end never cancels the job it
 * runs in (its next look is appended to that job, never a replacement of it).
 */
@Singleton
class GameSessionController @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val games: GamesRepository,
    private val platform: GamePlatform,
    private val modesRepository: ModesRepository,
    private val dashboardRepository: DailyDashboardRepository
) {
    val running: Flow<RunningGame?> = preferences.gameSession.map(RunningGame::decode)

    /** The running session, read as stored (it takes no lock). */
    suspend fun current(): RunningGame? = RunningGame.decode(preferences.gameSession.first())

    /**
     * Starts [page]. [startedMinutesAgo] backdates the start for a session started once already
     * playing (13.1: 10, 20 or 30 minutes, at most [MAX_EARLIER_MINUTES]). A running session is
     * stopped and kept first, ending where this one begins (13.4: the overlap never counts for both
     * games; one that began after that leaves no record). When that session can't be saved it keeps
     * running and nothing new starts: null, as on a read-only device. The game night follows the
     * moment of the tap; the notification goes out at once and gets the game's picture after.
     */
    suspend fun start(
        page: GamePage,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        startedMinutesAgo: Int = 0
    ): GameNightOutcome? {
        if (VaultWriteGate.refuses()) return null
        return withContext(NonCancellable) {
            lock.withLock {
                val startAt = now - startedMinutesAgo.coerceIn(0, MAX_EARLIER_MINUTES) * 60_000L
                if (current() != null && stopLocked(now, zone, endAt = startAt, fromCheck = false) == StopOutcome.Kept) {
                    return@withLock null
                }
                val session = RunningGame(startAt, page.path)
                val hasMap = page.maps.isNotEmpty()
                preferences.setGameSession(session.encode())
                platform.postRunning(session, page.title, hasMap)
                val check = SessionCheck.first(session)
                preferences.setGameSessionCheck(check.encode())
                platform.scheduleCheck(check.nextAskAt - now)
                val outcome = gameNightLocked(now, zone)
                // The picture never holds the notification back: it follows, once read.
                largeIconOf(page, session)?.let { icon -> platform.postRunning(session, page.title, hasMap, largeIcon = icon) }
                outcome
            }
        }
    }

    /**
     * Stops the running session and keeps it. It is recorded as ending at the earlier of [endAt] and
     * the time an unanswered "Still playing?" was due (13.4, E4: a Stop after the answer's deadline
     * ends at the deadline, not when someone notices), or now when neither applies; the game night
     * ends with it in every case. Saved before anything is cleared: a session that can't be saved
     * keeps running, its notification too, and a later Stop or check saves it under the same id.
     * The record; null when nothing ran, it lasted under a minute, or it was kept running.
     */
    suspend fun stop(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        endAt: Long? = null
    ): GameSessionRecord? = withContext(NonCancellable) {
        lock.withLock { (stopLocked(now, zone, endAt, fromCheck = false) as? StopOutcome.Stopped)?.record }
    }

    /**
     * The running session's check (13.1, the check worker): asks "Still playing?" once it is due,
     * ends a session nobody answered for at the time the question was due, and otherwise waits.
     * Each look also puts the session's notification back (gone after a reboot or a swipe). 13.4:
     * the next look is appended to the job running this one, never a replacement of it, so the job
     * ends by returning; an end that can't be saved is tried again [RETRY_MS] later.
     */
    suspend fun check(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (VaultWriteGate.readOnly) return
        // 13.4 review: a job cancelled while it waited for the lock still runs its look but schedules
        // none: start, restore or Keep playing replaced it and set the next one (a stop left no
        // session), and a job the system stopped is run again by WorkManager.
        val job = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            lock.withLock { checkLocked(now, zone, superseded = { job?.isCancelled == true }) }
        }
    }

    /** "Keep playing" (13.1): the question goes away and comes back in an hour; with no session, it just goes. */
    suspend fun keepPlaying(now: Long = System.currentTimeMillis()) {
        withContext(NonCancellable) {
            lock.withLock {
                val session = current() ?: run {
                    platform.cancelAsk()
                    return@withLock
                }
                val next = GameSessionGuard.keep(checkOf(session), now)
                preferences.setGameSessionCheck(next.encode())
                platform.cancelAsk()
                platform.scheduleCheck(next.nextAskAt - now)
            }
        }
    }

    /**
     * Puts a running session back when the app comes to the foreground (13.1): its notification
     * (gone after a reboot or a swipe) and its next check (a session started before 13.1 has none).
     * An unanswered question isn't posted again: the Games tab shows it. With no session (13.4),
     * whatever a stop left behind goes: the notifications, the check and the game night (each safe
     * to repeat, and the app owns that mode, which nobody can turn on by hand).
     */
    suspend fun restore(now: Long = System.currentTimeMillis()) {
        if (VaultWriteGate.readOnly) return
        withContext(NonCancellable) {
            lock.withLock {
                val session = current() ?: run {
                    platform.cancelRunning()
                    platform.cancelAsk()
                    preferences.setGameSessionCheck(null)
                    platform.endGameNight(preferences.gameNightRule.first())
                    return@withLock
                }
                val check = checkOf(session)
                val shown = shownFor(session)
                platform.postRunning(session, shown.title, shown.hasMap, shown.icon)
                preferences.setGameSessionCheck(check.encode())
                platform.scheduleCheck((check.askedAt?.plus(GameSessionGuard.ANSWER_MS) ?: check.nextAskAt) - now)
            }
        }
    }

    /** The running session's check, for the Games tab's "Still playing?" line. */
    val runningCheck: Flow<SessionCheck?> = preferences.gameSessionCheck.map(SessionCheck::decode)

    // --- Sessions fixed by hand (13.1, the owner's interview of 2026-11-16) ------------------

    /** A session the owner forgot to start; null when it couldn't be saved. */
    suspend fun addPastSession(
        page: GamePage,
        date: LocalDate,
        start: java.time.LocalTime,
        minutes: Int,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): GameSessionRecord? {
        if (VaultWriteGate.refuses()) return null
        val record = ManualSessions.record(page.path, date, start, minutes, zone) ?: return null
        games.addSessions(listOf(record)) ?: return null
        refreshDay(date, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return record
    }

    /** [old] with a new start and length (same day); null when it couldn't be saved. */
    suspend fun editSession(
        old: GameSessionRecord,
        start: java.time.LocalTime,
        minutes: Int,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): GameSessionRecord? {
        if (VaultWriteGate.refuses()) return null
        if (old.source != GameSessionRecord.SOURCE_MANUAL) return null
        val next = ManualSessions.edited(old, start, minutes, zone) ?: return null
        if (!games.replaceSession(old, next)) return null
        refreshDay(LocalDate.parse(next.date), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return next
    }

    /** Removes [record]; true when it was there. */
    suspend fun deleteSession(
        record: GameSessionRecord,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (VaultWriteGate.refuses()) return false
        val day = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: return false
        if (!games.removeSession(record.id, day)) return false
        refreshDay(day, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return true
    }

    /**
     * The day's Timeline line follows its sessions: today's note is burned (made if the day has no
     * board yet); an earlier day within a week gets its marked lines edited (D11), never a re-burn;
     * older days keep their sessions in the data file only.
     */
    private suspend fun refreshDay(day: LocalDate, today: LocalDate) {
        if (day.isBefore(today)) {
            games.applyPastDay(day, today)
        } else if (day == today) {
            dashboardRepository.ensureDay(day)
            dashboardRepository.burnToDisk(day)
        }
    }

    private suspend fun checkOf(session: RunningGame): SessionCheck =
        SessionCheck.decode(preferences.gameSessionCheck.first())?.takeIf { it.sessionStart == session.startMillis }
            ?: SessionCheck.first(session)

    private fun fallbackTitle(session: RunningGame): String =
        session.page.substringAfterLast('/').removeSuffix(".md").replace('_', ' ')

    /**
     * The game played last (RFC-009 §4.3: the session picker's default and the tile's game): the
     * latest of the sessions kept here and the consoles' last-played times (PSN's, and Xbox's since
     * 13.3), else the first game being played.
     */
    suspend fun defaultPage(zone: ZoneId = ZoneId.systemDefault()): GamePage? {
        val pages = games.pages()
        if (pages.isEmpty()) return null
        val links = games.links()
        val byPath = pages.associateBy { it.path }
        val resolve = games.resolver(pages, links)
        val today = LocalDate.now(zone)
        val fromSessions = games.sessionsBetween(today.minusDays(60), today).mapNotNull { record ->
            val page = resolve(record) ?: return@mapNotNull null
            val time = record.start?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: java.time.LocalTime.MIDNIGHT
            page to LocalDate.parse(record.date).atTime(time)
        }
        val fromPsn = games.snapshot()?.titles.orEmpty().mapNotNull { title ->
            val page = links.pageOf(title.titleId)?.let(byPath::get) ?: return@mapNotNull null
            val at = title.lastPlayed?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDateTime() }.getOrNull() } ?: return@mapNotNull null
            page to at
        }
        val fromXbox = games.xboxSnapshot()?.titles.orEmpty().mapNotNull { title ->
            val page = links.consolePage(GameConsole.XBOX, title.titleId)?.let(byPath::get) ?: return@mapNotNull null
            val at = title.lastPlayed?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDateTime() }.getOrNull() } ?: return@mapNotNull null
            page to at
        }
        return (fromSessions + fromPsn + fromXbox).maxByOrNull { it.second }?.first
            ?: pages.firstOrNull { it.state == GameState.PLAYING } ?: pages.first()
    }

    companion object {
        private const val TAG = "GameSession"

        /** "Started earlier" goes back at most an hour; longer is a past session. */
        const val MAX_EARLIER_MINUTES = 60

        /** An end the check couldn't save is tried again a quarter of an hour later (13.4). */
        const val RETRY_MS = 15 * 60_000L
    }
}
