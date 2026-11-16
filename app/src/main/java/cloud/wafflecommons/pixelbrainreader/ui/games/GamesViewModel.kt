package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameDays
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameHours
import cloud.wafflecommons.pixelbrainreader.data.games.GameNightOutcome
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.SessionCheck
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
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

/** One game of the library: its page and what PSN and the sessions say about it. */
data class GameRow(
    val page: GamePage,
    val hours: Double?,
    val trophyPercent: Int?,
    val lastPlayed: LocalDate?,
    val iconUrl: String?
)

/** A PSN title no page claims (the fold under the library). */
data class UnlinkedTitle(val titleId: String, val name: String, val hours: Double, val lastPlayed: LocalDate?)

/** The PSN line of the tab: hidden when the build has no client. */
data class PsnLine(val configured: Boolean, val connected: Boolean, val status: PsnStatus?, val expiresInDays: Long?)

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
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** A game night would have started, but Do Not Disturb access isn't granted yet. */
    val askAccess: Boolean = false
)

/** The Games tab (RFC-009 §4.1). */
@HiltViewModel
class GamesViewModel @Inject constructor(
    private val games: GamesRepository,
    private val controller: GameSessionController,
    private val account: PsnAccount,
    private val clock: AppClock
) : ViewModel() {

    private val _state = MutableStateFlow(GamesUiState())
    val state: StateFlow<GamesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A day change re-reads too: last played and the PSN line follow AppClock, never a captured date.
            combine(games.changes, controller.running, account.status, clock.today) { _, running, status, _ -> running to status }
                .collect { (running, status) -> reload(running, status) }
        }
        viewModelScope.launch { clock.foregroundEvents.collect { refresh() } }
        viewModelScope.launch {
            controller.runningCheck.collect { check -> _state.update { it.copy(runningCheck = check?.takeIf { c -> c.askedAt != null }) } }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload(controller.current(), null) }
    }

    private suspend fun reload(running: RunningGame?, status: PsnStatus?) {
        try {
            val pages = games.pages()
            val snapshot = games.snapshot()
            val links = games.links()
            val today = clock.today.value
            val sessions = games.sessionsBetween(today.minusDays(HISTORY_DAYS), today)
            val resolve = games.resolver(pages, links)
            val totals = GameDays.totals(sessions, resolve)
            val playedDays = sessions.groupBy { resolve(it)?.path }
                .mapValues { (_, list) -> list.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() } }
            val zone = ZoneId.systemDefault()
            val rows = pages.map { page ->
                val titles = links.titlesOf(page.path).mapNotNull { id -> snapshot?.titles?.firstOrNull { it.titleId == id } }
                val psnSeconds = titles.sumOf { it.playSeconds }
                GameRow(
                    page = page,
                    hours = GameHours.of(psnSeconds, page.playedBefore, totals[page.path] ?: 0),
                    trophyPercent = titles.mapNotNull { it.progress }.maxOrNull(),
                    lastPlayed = (titles.mapNotNull { date(it.lastPlayed, zone) } + playedDays[page.path].orEmpty()).maxOrNull(),
                    iconUrl = titles.firstNotNullOfOrNull { it.imageUrl }
                )
            }
            val byPath = rows.associateBy { it.page.path }
            val groups = GamePages.grouped(pages).mapValues { (_, list) -> list.mapNotNull { byPath[it.path] } }
            val tokens = account.tokens()
            _state.update {
                it.copy(
                    loading = false,
                    groups = groups,
                    pages = pages,
                    unlinked = unlinked(snapshot, links.links.keys, links.hidden.toSet(), zone),
                    running = running,
                    runningTitle = running?.let { r -> pages.firstOrNull { p -> p.path == r.page }?.title },
                    lastPlayedPath = controller.defaultPage()?.path,
                    psn = PsnLine(
                        configured = account.configured,
                        connected = tokens != null,
                        status = status ?: it.psn.status,
                        expiresInDays = tokens?.let { t -> (t.refreshExpiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 86_400_000L }
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

    fun link(titleId: String, page: GamePage) {
        viewModelScope.launch { games.updateLinks { it.link(titleId, page.path) } }
    }

    fun hide(titleId: String) {
        viewModelScope.launch { games.updateLinks { it.hide(titleId) } }
    }

    private fun unlinked(snapshot: PsnSnapshot?, linked: Set<String>, hidden: Set<String>, zone: ZoneId): List<UnlinkedTitle> =
        snapshot?.titles.orEmpty()
            .filter { it.titleId !in linked && it.titleId !in hidden && it.playSeconds > 0 }
            .sortedByDescending { it.lastPlayed ?: "" }
            .map { t: PsnTitleState -> UnlinkedTitle(t.titleId, t.name, t.playSeconds / 3600.0, date(t.lastPlayed, zone)) }

    private fun date(raw: String?, zone: ZoneId): LocalDate? = raw?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }

    private companion object {
        const val HISTORY_DAYS = 365L
    }
}
