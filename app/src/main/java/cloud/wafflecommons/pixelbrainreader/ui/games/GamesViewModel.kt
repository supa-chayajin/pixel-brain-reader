package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameDays
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameHours
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameNightOutcome
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.SessionCheck
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One game of the library: its page and what the consoles and the sessions say about it. */
data class GameRow(
    val page: GamePage,
    val hours: Double?,
    val trophyPercent: Int?,
    /** The share of the Xbox achievements earned (13.3): the best of the page's Xbox titles. */
    val achievementPercent: Int?,
    val lastPlayed: LocalDate?,
    val iconUrl: String?
)

/**
 * A console title no page claims (the folds under the library), or one still linked to a page that
 * was deleted. [hours] is null when the console doesn't say (an Xbox title without its minutes).
 */
data class UnlinkedTitle(
    val titleId: String,
    val name: String,
    val hours: Double?,
    val lastPlayed: LocalDate?,
    val console: GameConsole = GameConsole.PSN
)

/** The PSN line of the tab: hidden when the build has no client. */
data class PsnLine(val configured: Boolean, val connected: Boolean, val status: PsnStatus?, val expiresInDays: Long?)

/** The Xbox line of the tab (13.3): hidden when the build has no Xbox app. */
data class XboxLine(val configured: Boolean, val connected: Boolean, val status: XboxStatus?)

data class GamesUiState(
    val loading: Boolean = true,
    val groups: Map<GameGroup, List<GameRow>> = emptyMap(),
    val pages: List<GamePage> = emptyList(),
    val unlinked: List<UnlinkedTitle> = emptyList(),
    val running: RunningGame? = null,
    val runningTitle: String? = null,
    /** The running session's "Still playing?", while unanswered (13.1). */
    val runningCheck: SessionCheck? = null,
    /** The game played last: the session picker's default (RFC-009 §4.3). */
    val lastPlayedPath: String? = null,
    val psn: PsnLine = PsnLine(configured = false, connected = false, status = null, expiresInDays = null),
    val xbox: XboxLine = XboxLine(configured = false, connected = false, status = null),
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** A game night would have started, but Do Not Disturb access isn't granted yet. */
    val askAccess: Boolean = false
)

/** The Games tab (RFC-009 §4.1; 13.3: the Xbox beside the PlayStation). */
@HiltViewModel
class GamesViewModel @Inject constructor(
    private val games: GamesRepository,
    private val controller: GameSessionController,
    private val account: PsnAccount,
    private val xboxAccount: XboxAccount,
    private val clock: AppClock
) : ViewModel() {

    private val _state = MutableStateFlow(GamesUiState())
    val state: StateFlow<GamesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A day change re-reads too: last played and the console lines follow AppClock, never a captured date.
            combine(games.changes, controller.running, account.status, xboxAccount.status, clock.today) { _, running, status, xbox, _ ->
                Triple(running, status, xbox)
            }.collect { (running, status, xbox) -> reload(running, status, xbox) }
        }
        viewModelScope.launch { clock.foregroundEvents.collect { refresh() } }
        viewModelScope.launch {
            controller.runningCheck.collect { check -> _state.update { it.copy(runningCheck = check?.takeIf { c -> c.askedAt != null }) } }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload(controller.current(), null, null) }
    }

    /** [status] and [xboxStatus] null: the lines keep the status they show. */
    private suspend fun reload(running: RunningGame?, status: PsnStatus?, xboxStatus: XboxStatus?) {
        try {
            val pages = games.pages()
            val snapshot = games.snapshot()
            val xboxSnapshot = games.xboxSnapshot()
            val links = games.links()
            val today = clock.today.value
            val sessions = games.sessionsBetween(today.minusDays(HISTORY_DAYS), today)
            val resolve = games.resolver(pages, links)
            val totals = GameDays.totals(sessions, resolve)
            val playedDays = sessions.groupBy { resolve(it)?.path }
                .mapValues { (_, list) -> list.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() } }
            val zone = ZoneId.systemDefault()
            val rows = pages.map { page ->
                GamesLibrary.row(
                    page = page,
                    psn = links.titlesOf(page.path).mapNotNull { id -> snapshot?.titles?.firstOrNull { it.titleId == id } },
                    xbox = links.consoleTitles(GameConsole.XBOX, page.path).mapNotNull { id -> xboxSnapshot?.titles?.firstOrNull { it.titleId == id } },
                    sessionMinutes = totals[page.path] ?: 0,
                    playedDays = playedDays[page.path].orEmpty(),
                    zone = zone
                )
            }
            val byPath = rows.associateBy { it.page.path }
            val groups = GamePages.grouped(pages).mapValues { (_, list) -> list.mapNotNull { byPath[it.path] } }
            val tokens = account.tokens()
            // A build without the Xbox app never reads its sign-in.
            val xboxConnected = xboxAccount.configured && xboxAccount.isConnected()
            _state.update {
                it.copy(
                    loading = false,
                    groups = groups,
                    pages = pages,
                    unlinked = GamesLibrary.unlinked(snapshot, xboxSnapshot, links, pages.mapTo(HashSet()) { p -> p.path }, zone),
                    running = running,
                    runningTitle = running?.let { r -> pages.firstOrNull { p -> p.path == r.page }?.title },
                    lastPlayedPath = controller.defaultPage()?.path,
                    psn = PsnLine(
                        configured = account.configured,
                        connected = tokens != null,
                        status = status ?: it.psn.status,
                        expiresInDays = tokens?.let { t -> (t.refreshExpiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 86_400_000L }
                    ),
                    xbox = XboxLine(
                        configured = xboxAccount.configured,
                        connected = xboxConnected,
                        status = xboxStatus ?: it.xbox.status
                    ),
                    readOnly = VaultWriteGate.readOnly
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false) }
        }
    }

    /** Starts [page], [minutesAgo] earlier when the owner was already playing (13.1). */
    fun start(page: GamePage, minutesAgo: Int = 0) {
        viewModelScope.launch {
            val outcome = controller.start(page, startedMinutesAgo = minutesAgo)
            if (outcome == GameNightOutcome.NEEDS_ACCESS) _state.update { it.copy(askAccess = true) }
        }
    }

    /** "Still playing?" answered from the tab (13.1). */
    fun keepPlaying() {
        viewModelScope.launch { controller.keepPlaying() }
    }

    fun stop() {
        viewModelScope.launch { controller.stop() }
    }

    fun dismissAccess() = _state.update { it.copy(askAccess = false) }

    /** [title] linked to [page], among its own console's links (13.3: Xbox's apart from PSN's). */
    fun link(title: UnlinkedTitle, page: GamePage) {
        viewModelScope.launch { games.updateLinks { it.linkConsole(title.console, title.titleId, page.path) } }
    }

    /** [title] out of its console's fold; a dangling link goes with it. */
    fun hide(title: UnlinkedTitle) {
        viewModelScope.launch { games.updateLinks { it.hideConsole(title.console, title.titleId) } }
    }

    private companion object {
        const val HISTORY_DAYS = 365L
    }
}

/** The library's rows and folds, from the pages, both consoles' snapshots and the sessions (pure, 13.3). */
internal object GamesLibrary {

    /**
     * [page]'s row; [psn] and [xbox] are the page's linked titles of each console. The hours add both
     * consoles' totals once either measures the game, else `played_before` plus the sessions (XB3);
     * the icon is PlayStation's, else Xbox's.
     */
    fun row(
        page: GamePage,
        psn: List<PsnTitleState>,
        xbox: List<XboxTitleState>,
        sessionMinutes: Int,
        playedDays: List<LocalDate>,
        zone: ZoneId
    ): GameRow = GameRow(
        page = page,
        hours = GameHours.of(
            psn.sumOf { it.playSeconds },
            page.playedBefore,
            sessionMinutes,
            xboxMinutes = xbox.sumOf { it.minutesPlayed ?: 0L }
        ),
        trophyPercent = psn.mapNotNull { it.progress }.maxOrNull(),
        achievementPercent = xbox.mapNotNull { achievementPercent(it) }.maxOrNull(),
        lastPlayed = (psn.mapNotNull { date(it.lastPlayed, zone) } + xbox.mapNotNull { date(it.lastPlayed, zone) } + playedDays).maxOrNull(),
        iconUrl = psn.firstNotNullOfOrNull { it.imageUrl } ?: xbox.firstNotNullOfOrNull { it.imageUrl }
    )

    /**
     * The titles no page claims, both consoles, the latest played first: no link, or a link to a page
     * that isn't in [pagePaths] any more (the owner deleted it: no new page comes, the fold offers it
     * again). Each console's hidden titles stay out; a PSN title needs play time, an Xbox one is
     * listed whatever its minutes (null hours while Xbox doesn't say).
     */
    fun unlinked(psn: PsnSnapshot?, xbox: XboxSnapshot?, links: GameLinks, pagePaths: Set<String>, zone: ZoneId): List<UnlinkedTitle> {
        fun claimed(console: GameConsole, titleId: String): Boolean = links.consolePage(console, titleId)?.let { it in pagePaths } ?: false
        val fromPsn = psn?.titles.orEmpty()
            .filter { it.playSeconds > 0 && !claimed(GameConsole.PSN, it.titleId) && !links.isHidden(GameConsole.PSN, it.titleId) }
            .map { t -> instant(t.lastPlayed) to UnlinkedTitle(t.titleId, t.name, t.playSeconds / 3600.0, date(t.lastPlayed, zone), GameConsole.PSN) }
        val fromXbox = xbox?.titles.orEmpty()
            .filter { !claimed(GameConsole.XBOX, it.titleId) && !links.isHidden(GameConsole.XBOX, it.titleId) }
            .map { t ->
                instant(t.lastPlayed) to UnlinkedTitle(t.titleId, t.name, t.minutesPlayed?.let { it / 60.0 }, date(t.lastPlayed, zone), GameConsole.XBOX)
            }
        // Newest first; a title without a last played time goes last.
        return (fromPsn + fromXbox).sortedByDescending { it.first }.map { it.second }
    }

    /** The earned share from the achievements file's total (kept in the snapshot), else titlehub's progress. */
    private fun achievementPercent(title: XboxTitleState): Int? {
        val total = title.totalAchievements?.takeIf { it > 0 }
        val earned = title.currentAchievements
        return if (total != null && earned != null) (earned * 100 / total).coerceIn(0, 100) else title.progress
    }

    private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun date(raw: String?, zone: ZoneId): LocalDate? = instant(raw)?.atZone(zone)?.toLocalDate()
}
