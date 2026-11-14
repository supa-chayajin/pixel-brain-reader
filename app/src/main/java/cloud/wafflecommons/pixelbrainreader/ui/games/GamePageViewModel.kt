package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.ChecklistSection
import cloud.wafflecommons.pixelbrainreader.data.games.GameChecklists
import cloud.wafflecommons.pixelbrainreader.data.games.GameDays
import cloud.wafflecommons.pixelbrainreader.data.games.GameDayLine
import cloud.wafflecommons.pixelbrainreader.data.games.GameHistoryEntry
import cloud.wafflecommons.pixelbrainreader.data.games.GameNightOutcome
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.TrophyRecord
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySetFile
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One trophy as the page lists it: [earnedOn] null while not earned. */
data class TrophyLine(val set: String, val trophy: TrophyRecord, val earnedOn: LocalDate?)

data class GamePageUiState(
    val loading: Boolean = true,
    val page: GamePage? = null,
    val hours: Double? = null,
    val trophiesEarned: Int = 0,
    val trophiesTotal: Int = 0,
    val trophies: List<TrophyLine> = emptyList(),
    val checklists: List<ChecklistSection> = emptyList(),
    /** A line per day played, newest first. */
    val days: List<Pair<LocalDate, GameDayLine>> = emptyList(),
    val history: List<GameHistoryEntry> = emptyList(),
    val folderNotes: List<String> = emptyList(),
    val running: RunningGame? = null,
    val askProject: String? = null,
    val readOnly: Boolean = VaultWriteGate.readOnly,
    val askAccess: Boolean = false
)

/** A game's page (RFC-009 §4.2): route `game?page=<path>`. */
@HiltViewModel
class GamePageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val games: GamesRepository,
    private val controller: GameSessionController,
    private val clock: AppClock
) : ViewModel() {

    private val path: String = savedStateHandle.get<String>("page").orEmpty()

    /** The earlier Timeline lines naming this game: read once (game writes don't change them). */
    private var history: List<GameHistoryEntry>? = null

    private val _state = MutableStateFlow(GamePageUiState())
    val state: StateFlow<GamePageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(games.changes, controller.running, clock.today) { _, running, today -> running to today }
                .collect { (running, today) -> load(running, today) }
        }
    }

    private suspend fun load(running: RunningGame?, today: LocalDate) {
        try {
            val content = games.pageContent(path)
            val page = content?.let { cloud.wafflecommons.pixelbrainreader.data.games.GamePages.parse(path, it) }
            if (page == null) {
                _state.update { it.copy(loading = false, page = null) }
                return
            }
            val pages = games.pages()
            val links = games.links()
            val snapshot = games.snapshot()
            val titles = links.titlesOf(path).mapNotNull { id -> snapshot?.titles?.firstOrNull { it.titleId == id } }
            val sets: List<TrophySetFile> = titles.mapNotNull { it.trophySet }.distinct().mapNotNull { games.trophySet(it) }
            val zone = java.time.ZoneId.systemDefault()
            val trophies = sets.flatMap { set ->
                set.trophies.map { t ->
                    TrophyLine(set.set, t, t.earnedAt?.let { runCatching { java.time.Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() })
                }
            }.sortedWith(compareBy<TrophyLine>({ it.earnedOn == null }, { it.earnedOn }, { it.trophy.id }))
            val resolve = games.resolver(pages, links)
            val sessions = games.sessionsBetween(today.minusDays(DAYS), today).filter { resolve(it)?.path == path }
            val days = sessions.map { it.date }.distinct().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .flatMap { day -> GameDays.lines(day, sessions, resolve).map { line -> day to line } }
                .sortedByDescending { it.first }
            val psnSeconds = titles.sumOf { it.playSeconds }
            _state.update {
                it.copy(
                    loading = false,
                    page = page,
                    hours = if (psnSeconds > 0) psnSeconds / 3600.0 else days.sumOf { d -> d.second.minutes }.takeIf { m -> m > 0 }?.let { m -> m / 60.0 },
                    trophiesEarned = trophies.count { t -> t.earnedOn != null },
                    trophiesTotal = trophies.size,
                    trophies = trophies,
                    checklists = GameChecklists.sections(content),
                    days = days,
                    history = history ?: games.history(listOf(page)).also { history = it },
                    folderNotes = games.folderNotes(page),
                    running = running,
                    askProject = games.config().askProject,
                    readOnly = VaultWriteGate.readOnly
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false) }
        }
    }

    fun setState(state: GameState) {
        viewModelScope.launch { games.setState(path, state, clock.today.value) }
    }

    fun toggle(line: String) {
        viewModelScope.launch { games.editPage(path) { content -> GameChecklists.toggle(content, line) ?: content } }
    }

    fun start() {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            if (controller.start(page) == GameNightOutcome.NEEDS_ACCESS) _state.update { it.copy(askAccess = true) }
        }
    }

    fun stop() {
        viewModelScope.launch { controller.stop() }
    }

    fun dismissAccess() = _state.update { it.copy(askAccess = false) }

    private companion object {
        const val DAYS = 365L
    }
}
