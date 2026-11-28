package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementRecord
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import cloud.wafflecommons.pixelbrainreader.data.games.ChecklistSection
import cloud.wafflecommons.pixelbrainreader.data.games.GameChecklists
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameDays
import cloud.wafflecommons.pixelbrainreader.data.games.GameDayLine
import cloud.wafflecommons.pixelbrainreader.data.games.GameHistoryEntry
import cloud.wafflecommons.pixelbrainreader.data.games.GameHours
import cloud.wafflecommons.pixelbrainreader.data.games.GameMapEdits
import cloud.wafflecommons.pixelbrainreader.data.games.GameNightOutcome
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.MapEditResult
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.TrophyRecord
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySetFile
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** One trophy as the page lists it: [earnedOn] null while not earned. */
data class TrophyLine(val set: String, val trophy: TrophyRecord, val earnedOn: LocalDate?)

/**
 * One Xbox achievement as the page lists it (13.3, XB-E): [earnedOn] null while not earned;
 * [setTitle] is its title's name, shown when the page holds more than one title (a 360 version
 * beside the One one).
 */
data class AchievementLine(val titleId: String, val setTitle: String, val record: AchievementRecord, val earnedOn: LocalDate?)

/**
 * The page's achievements (pure): the earned ones first, by the day they were earned in [zone],
 * then the others; within each, by numeric id (Microsoft's ids are digits in practice), then id.
 * An earned date that doesn't read counts as not earned, like a trophy's.
 */
internal fun achievementLines(files: List<AchievementSetFile>, zone: ZoneId): List<AchievementLine> =
    files.flatMap { file ->
        file.achievements.map { record ->
            AchievementLine(
                titleId = file.titleId,
                setTitle = file.title,
                record = record,
                earnedOn = record.earnedAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
            )
        }
    }.sortedWith(
        compareBy<AchievementLine>(
            { it.earnedOn == null },
            { it.earnedOn },
            { it.record.id.toIntOrNull() ?: Int.MAX_VALUE },
            { it.record.id }
        )
    )

/** What a picture's save says when it didn't go through (13.4, P5). */
internal const val COVER_NOT_SAVED = "The picture couldn't be saved."

/**
 * What a cover edit says (13.4, P5), on the game's page and after a shared image: [done] once it
 * went through, [failed] when it didn't, the read-only notice on a read-only device.
 */
internal fun coverMessage(result: CoverEditResult, done: String, failed: String = COVER_NOT_SAVED): String = when (result) {
    CoverEditResult.SAVED -> done
    CoverEditResult.READ_ONLY -> READ_ONLY_NOTICE
    CoverEditResult.GONE -> "This game's page is gone."
    CoverEditResult.NOTHING_TO_REMOVE -> "This game has no picture of its own."
    CoverEditResult.FAILED -> failed
}

data class GamePageUiState(
    val loading: Boolean = true,
    val page: GamePage? = null,
    val hours: Double? = null,
    val trophiesEarned: Int = 0,
    val trophiesTotal: Int = 0,
    val trophies: List<TrophyLine> = emptyList(),
    /** 13.3: the Xbox titles' achievements, earned first (XB2: beside the trophies). */
    val achievements: List<AchievementLine> = emptyList(),
    val achievementsEarned: Int = 0,
    val achievementsTotal: Int = 0,
    val gamerscoreEarned: Int = 0,
    val gamerscoreTotal: Int = 0,
    /** How many Xbox titles' files the achievements come from: more than one names each line's title. */
    val achievementSets: Int = 0,
    /** A console measured the game (XB3): the typed play time no longer counts. */
    val measured: Boolean = false,
    /** `game_maps` entries the app can't open, kept in the note as typed (13.3, ME1). */
    val unreadableMaps: Int = 0,
    val checklists: List<ChecklistSection> = emptyList(),
    /** The page's own text, without its checklists and 🏆 section (shown as cards). */
    val prose: String = "",
    /** The game's icon: PSN's when a linked title has one, else Xbox's. */
    val iconUrl: String? = null,
    /** A line per day played, newest first. */
    val days: List<Pair<LocalDate, GameDayLine>> = emptyList(),
    /** Each session of the last year, newest first: the manual ones can be fixed (13.1). */
    val sessions: List<GameSessionRecord> = emptyList(),
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

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** What each map edit did (13.3, ME1), for the page's snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(games.changes, controller.running, clock.today) { _, running, today -> running to today }
                .collect { (running, today) -> load(running, today) }
        }
    }

    private suspend fun load(running: RunningGame?, today: LocalDate) {
        try {
            val content = games.pageContent(path)
            val page = content?.let { GamePages.parse(path, it) }
            if (page == null) {
                _state.update { it.copy(loading = false, page = null) }
                return
            }
            val pages = games.pages()
            val links = games.links()
            val snapshot = games.snapshot()
            val titles = links.titlesOf(path).mapNotNull { id -> snapshot?.titles?.firstOrNull { it.titleId == id } }
            // 13.4: every trophy list of each title (a collection carries one per game), not its first.
            val sets: List<TrophySetFile> = titles.flatMap { it.allTrophySets }.distinct().mapNotNull { games.trophySet(it) }
            val zone = ZoneId.systemDefault()
            val trophies = sets.flatMap { set ->
                set.trophies.map { t ->
                    TrophyLine(set.set, t, t.earnedAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() })
                }
            }.sortedWith(compareBy<TrophyLine>({ it.earnedOn == null }, { it.earnedOn }, { it.trophy.id }))
            // 13.3: the Xbox titles linked to this page, their achievements files (read by the linked
            // ids, as the sync's 🎖️ section is) and their measured minutes (XB3).
            val xboxIds = links.consoleTitles(GameConsole.XBOX, path)
            val xbox = if (xboxIds.isEmpty()) null else games.xboxSnapshot()
            val xboxTitles = xboxIds.mapNotNull { id -> xbox?.titles?.firstOrNull { it.titleId == id } }
            val files = xboxIds.mapNotNull { games.achievementSet(it) }.filter { it.achievements.isNotEmpty() }
            val achievements = achievementLines(files, zone)
            val earnedAchievements = achievements.filter { it.earnedOn != null }
            val resolve = games.resolver(pages, links)
            // 13.4: the typed hours count every month on disk (the last year's alone made them shrink day
            // after day, and an older forgotten session never counted); the list keeps the last year.
            val mine = games.allSessions().filter { resolve(it)?.path == path }
            val year = today.minusDays(DAYS).toString()..today.toString()
            val sessions = mine.filter { it.date in year }
            val days = sessions.map { it.date }.distinct().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .flatMap { day -> GameDays.lines(day, sessions, resolve).map { line -> day to line } }
                .sortedByDescending { it.first }
            val psnSeconds = titles.sumOf { it.playSeconds }
            val xboxMinutes = xboxTitles.sumOf { it.minutesPlayed ?: 0L }
            val manualMinutes = GameDays.totals(mine, resolve)[path] ?: 0
            // The picture is decoration: its own read, never an error (the initials then).
            val art = pictures.artFor(page)
            _state.update {
                it.copy(
                    loading = false,
                    page = page,
                    hours = GameHours.of(psnSeconds, page.playedBefore, manualMinutes, xboxMinutes = xboxMinutes),
                    trophiesEarned = trophies.count { t -> t.earnedOn != null },
                    trophiesTotal = trophies.size,
                    trophies = trophies,
                    achievements = achievements,
                    achievementsEarned = earnedAchievements.size,
                    achievementsTotal = achievements.size,
                    gamerscoreEarned = earnedAchievements.sumOf { a -> a.record.gamerscore },
                    gamerscoreTotal = files.sumOf { f -> f.totalGamerscore },
                    achievementSets = files.size,
                    measured = psnSeconds > 0 || xboxMinutes > 0,
                    unreadableMaps = GameMapEdits.unreadable(content),
                    checklists = GameChecklists.sections(content),
                    prose = GamePages.prose(content),
                    iconUrl = titles.firstNotNullOfOrNull { t -> t.imageUrl } ?: xboxTitles.firstNotNullOfOrNull { t -> t.imageUrl },
                    days = days,
                    sessions = sessions.sortedWith(compareByDescending<GameSessionRecord> { s -> s.date }.thenByDescending { s -> s.start ?: "" }),
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

    /**
     * Ticks or unticks [item]'s own line (13.4, the audit's checklist-duplicate-line-tick): the line
     * at its index, read from disk, and only while it is still the one shown, so the same text under
     * another heading is never the one ticked; a page changed meanwhile is left as it is.
     */
    fun toggle(item: ChecklistItem) {
        viewModelScope.launch { games.editPage(path) { content -> GameChecklists.toggle(content, item.index, item.line) ?: content } }
    }

    // --- The owner's picture (13.4, P5): the page's own file, named in its frontmatter ----------

    /** [uri]'s image (the photo picker's) as this game's picture: re-encoded on the phone, kept beside the page. */
    fun setCover(uri: Uri) {
        if (_state.value.coverBusy) return
        _state.update { it.copy(coverBusy = true) }
        viewModelScope.launch {
            val message = try {
                val jpeg = photoReader.prepareCover(uri)
                coverMessage(games.setCover(path, jpeg, clock.today.value), done = "Picture saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "That image can't be read"
            } catch (e: Exception) {
                COVER_NOT_SAVED
            } finally {
                _state.update { it.copy(coverBusy = false) }
            }
            _messages.send(message)
        }
    }

    /** The game's own picture taken away: the console's shows again, or the initials. */
    fun removeCover() {
        if (_state.value.coverBusy) return
        _state.update { it.copy(coverBusy = true) }
        viewModelScope.launch {
            val message = try {
                coverMessage(games.removeCover(path, clock.today.value), done = "Picture removed", failed = COVER_NOT_REMOVED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                COVER_NOT_REMOVED
            } finally {
                _state.update { it.copy(coverBusy = false) }
            }
            _messages.send(message)
        }
    }

    // --- Maps (13.3, ME1): each edit reads the page from disk and names the map by its address ---

    fun addMap(url: String, label: String?) =
        editMaps(saved = "Map added") { games.addMap(path, url, label, clock.today.value) }

    /** The map at [oldUrl] given the address [url] and the label [label], in its place. */
    fun changeMap(oldUrl: String, url: String, label: String?) =
        editMaps(saved = "Map saved") { games.changeMap(path, oldUrl, url, label, clock.today.value) }

    fun removeMap(url: String) =
        editMaps(saved = "Map removed") { games.removeMap(path, url, clock.today.value) }

    /** [url]'s map moved first: the game's main map. */
    fun makeMainMap(url: String) =
        editMaps(saved = "Main map set") { games.makeMainMap(path, url, clock.today.value) }

    /** Runs one map edit and says how it went; a saved edit reloads the page through [GamesRepository.changes]. */
    private fun editMaps(saved: String, edit: suspend () -> MapEditResult) {
        viewModelScope.launch {
            val result = try {
                edit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapEditResult.FAILED
            }
            _messages.send(result.message(saved))
        }
    }

    /** Starts a session, [minutesAgo] earlier when the owner was already playing (13.1). */
    fun start(minutesAgo: Int = 0) {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            val outcome = controller.start(page, startedMinutesAgo = minutesAgo)
            if (outcome == GameNightOutcome.NEEDS_ACCESS) {
                _state.update { it.copy(askAccess = true) }
            } else if (outcome == null && !VaultWriteGate.readOnly && controller.current()?.page != page.path) {
                // 13.4 review: the game being played couldn't be saved, so it keeps running and this one doesn't start.
                _messages.send("The game being played couldn't be saved, so it keeps running")
            }
        }
    }

    /** A session the owner forgot to start (13.1). */
    fun addSession(date: LocalDate, start: LocalTime, minutes: Int) {
        val page = _state.value.page ?: return
        viewModelScope.launch { controller.addPastSession(page, date, start, minutes) }
    }

    fun editSession(record: GameSessionRecord, start: LocalTime, minutes: Int) {
        viewModelScope.launch { controller.editSession(record, start, minutes) }
    }

    fun deleteSession(record: GameSessionRecord) {
        viewModelScope.launch { controller.deleteSession(record) }
    }

    /** The play time typed from the console, in hours; blank clears it (13.1). */
    fun setPlayedBefore(text: String) {
        val hours = if (text.isBlank()) null else GamePages.hours(text) ?: return
        viewModelScope.launch { games.setPlayedBefore(path, hours, clock.today.value) }
    }

    fun stop() {
        viewModelScope.launch { controller.stop() }
    }

    fun dismissAccess() = _state.update { it.copy(askAccess = false) }

    private companion object {
        const val DAYS = 365L
    }
}
