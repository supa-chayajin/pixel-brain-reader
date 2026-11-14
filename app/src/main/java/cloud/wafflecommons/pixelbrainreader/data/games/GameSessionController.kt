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

    /** Starts [page]; a running session is stopped (and kept) first. Null on a read-only device. */
    suspend fun start(page: GamePage, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): GameNightOutcome? {
        if (VaultWriteGate.refuses()) return null
        if (current() != null) stop(now, zone)
        val session = RunningGame(now, page.path)
        preferences.setGameSession(session.encode())
        platform.postRunning(session, page.title)
        val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val config = modesRepository.currentConfig()
        if (!GameNight.guards(modesRepository.currentVisibility().mode, ModeResolver.inWorkHours(config, moment))) return GameNightOutcome.NONE
        preferences.setGameNightDay(moment.toLocalDate())
        if (!platform.canGuard()) return GameNightOutcome.NEEDS_ACCESS
        val rule = platform.startGameNight(preferences.gameNightRule.first())
        preferences.setGameNightRule(rule)
        return if (rule != null) GameNightOutcome.ON else GameNightOutcome.NEEDS_ACCESS
    }

    /** Stops the running session and keeps it; the game night ends with it. */
    suspend fun stop(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): GameSessionRecord? {
        val session = current()
        preferences.setGameSession(null)
        platform.cancelRunning()
        platform.endGameNight(preferences.gameNightRule.first())
        if (session == null || VaultWriteGate.readOnly) return null
        val record = session.record(now, zone) ?: return null
        games.addSessions(listOf(record)) ?: return null
        // Today's note gets its line now, even on a day with nothing else on the board; a session
        // started yesterday evening goes to that day's note (D11), never re-burned.
        val day = LocalDate.parse(record.date)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (day.isBefore(today)) {
            games.applyPastDay(day, today)
        } else {
            dashboardRepository.ensureDay(day)
            dashboardRepository.burnToDisk(day)
        }
        return record
    }

    /**
     * The game played last (RFC-009 §4.3: the session picker's default and the tile's game): the
     * latest of the sessions kept here and PSN's last-played times, else the first game being played.
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
        return (fromSessions + fromPsn).maxByOrNull { it.second }?.first
            ?: pages.firstOrNull { it.state == GameState.PLAYING } ?: pages.first()
    }
}
