package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    suspend fun current(): RunningGame? = RunningGame.decode(preferences.gameSession.first())

    /**
     * Starts [page]; a running session is stopped (and kept) first. [startedMinutesAgo] backdates the
     * start for a session started once already playing (13.1: 10, 20 or 30 minutes); the game night
     * follows the moment of the tap. Null on a read-only device.
     */
    suspend fun start(
        page: GamePage,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        startedMinutesAgo: Int = 0
    ): GameNightOutcome? {
        if (VaultWriteGate.refuses()) return null
        if (current() != null) stop(now, zone)
        val session = RunningGame(now - startedMinutesAgo.coerceIn(0, MAX_EARLIER_MINUTES) * 60_000L, page.path)
        preferences.setGameSession(session.encode())
        platform.postRunning(session, page.title, hasMap = page.maps.isNotEmpty())
        val check = SessionCheck.first(session)
        preferences.setGameSessionCheck(check.encode())
        platform.scheduleCheck(check.nextAskAt - now)
        val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val config = modesRepository.currentConfig()
        if (!GameNight.guards(modesRepository.currentVisibility().mode, ModeResolver.inWorkHours(config, moment))) return GameNightOutcome.NONE
        preferences.setGameNightDay(moment.toLocalDate())
        if (!platform.canGuard()) return GameNightOutcome.NEEDS_ACCESS
        val rule = platform.startGameNight(preferences.gameNightRule.first())
        preferences.setGameNightRule(rule)
        return if (rule != null) GameNightOutcome.ON else GameNightOutcome.NEEDS_ACCESS
    }

    /**
     * Stops the running session and keeps it, recorded as ending at [endAt] (now, or the time a
     * "Still playing?" nobody answered was due); the game night ends with it.
     */
    suspend fun stop(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        endAt: Long = now
    ): GameSessionRecord? {
        val session = current()
        preferences.setGameSession(null)
        preferences.setGameSessionCheck(null)
        platform.cancelRunning()
        platform.cancelAsk()
        platform.cancelCheck()
        platform.endGameNight(preferences.gameNightRule.first())
        if (session == null || VaultWriteGate.readOnly) return null
        val record = session.record(minOf(endAt, now), zone) ?: return null
        games.addSessions(listOf(record)) ?: return null
        refreshDay(LocalDate.parse(record.date), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return record
    }

    /**
     * The running session's check (13.1, the check worker): asks "Still playing?" once it is due,
     * ends a session nobody answered for at the time the question was due, and otherwise waits.
     * Each look also puts the session's notification back (gone after a reboot or a swipe).
     */
    suspend fun check(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (VaultWriteGate.readOnly) return
        val session = current() ?: run {
            preferences.setGameSessionCheck(null)
            platform.cancelAsk()
            return
        }
        val check = checkOf(session)
        val page = games.page(session.page)
        val title = page?.title ?: fallbackTitle(session)
        val hasMap = page?.maps?.isNotEmpty() == true
        when (val step = GameSessionGuard.step(check, now)) {
            is GameSessionGuard.Step.Wait -> {
                platform.postRunning(session, title, hasMap)
                preferences.setGameSessionCheck(check.encode())
                platform.scheduleCheck(step.at - now)
            }
            is GameSessionGuard.Step.Ask -> {
                platform.postRunning(session, title, hasMap)
                preferences.setGameSessionCheck(check.copy(askedAt = now).encode())
                platform.postAsk(title, session.startMillis, endsAt = check.nextAskAt, answerBy = step.answerBy)
                platform.scheduleCheck(step.answerBy - now)
            }
            is GameSessionGuard.Step.End -> stop(now, zone, endAt = step.at)
        }
    }

    /** "Keep playing" (13.1): the question goes away and comes back in an hour. */
    suspend fun keepPlaying(now: Long = System.currentTimeMillis()) {
        val session = current() ?: return
        val next = GameSessionGuard.keep(checkOf(session), now)
        preferences.setGameSessionCheck(next.encode())
        platform.cancelAsk()
        platform.scheduleCheck(next.nextAskAt - now)
    }

    /**
     * Puts a running session back when the app comes to the foreground (13.1): its notification
     * (gone after a reboot or a swipe) and its next check (a session started before 13.1 has none).
     * An unanswered question isn't posted again: the Games tab shows it. Nothing when no session runs.
     */
    suspend fun restore(now: Long = System.currentTimeMillis()) {
        if (VaultWriteGate.readOnly) return
        val session = current() ?: return
        val check = checkOf(session)
        val page = games.page(session.page)
        platform.postRunning(session, page?.title ?: fallbackTitle(session), hasMap = page?.maps?.isNotEmpty() == true)
        preferences.setGameSessionCheck(check.encode())
        platform.scheduleCheck((check.askedAt?.plus(GameSessionGuard.ANSWER_MS) ?: check.nextAskAt) - now)
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
        /** "Started earlier" goes back at most an hour; longer is a past session. */
        const val MAX_EARLIER_MINUTES = 60
    }
}
