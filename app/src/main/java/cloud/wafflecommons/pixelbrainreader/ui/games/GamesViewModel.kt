package cloud.wafflecommons.pixelbrainreader.ui.games

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameDays
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameHours
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GameNightOutcome
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.LastPlayed
import cloud.wafflecommons.pixelbrainreader.data.games.LinksRead
import cloud.wafflecommons.pixelbrainreader.data.games.OrphanedPage
import cloud.wafflecommons.pixelbrainreader.data.games.OrphanedSessions
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.SessionCheck
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnException
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxException
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.di.AppScopeErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    /** The game's picture (13.4, P1): the owner's, else PlayStation's, else Xbox's, else its initials. */
    val art: GameArt
)

/**
 * A console title no page claims (the folds under the library), or one still linked to a page that
 * was deleted; also a title the owner hid (13.4: the Hidden folds). [hours] is null when the console
 * doesn't say (an Xbox title without its minutes). [looksLike] (13.4, the owner's call): a page whose
 * name begins with this title, word for word, offered in one tap and never linked on its own.
 */
data class UnlinkedTitle(
    val titleId: String,
    val name: String,
    val hours: Double?,
    val lastPlayed: LocalDate?,
    val console: GameConsole = GameConsole.PSN,
    val art: GameArt = GameArtRules.initialsOnly(name),
    val looksLike: GamePage? = null
) {
    /** The title in [GamesUiState.pendingHidden]: its console and id. */
    val foldKey: String get() = "$console:$titleId"
}

/**
 * The PSN line of the tab: hidden when the build has no client. [syncing] (13.4): a sync runs now;
 * [readOnly]: a read-only device, where PlayStation syncs on the phone.
 */
data class PsnLine(
    val configured: Boolean,
    val connected: Boolean,
    val status: PsnStatus?,
    val expiresInDays: Long?,
    val syncing: Boolean = false,
    val readOnly: Boolean = false
)

/** The Xbox line of the tab (13.3): hidden when the build has no Xbox app. [syncing] and [readOnly] as PSN's. */
data class XboxLine(
    val configured: Boolean,
    val connected: Boolean,
    val status: XboxStatus?,
    val syncing: Boolean = false,
    val readOnly: Boolean = false
)

data class GamesUiState(
    /**
     * AppClock's day at the last load (13.4): every "today" and "yesterday" on the tab follows it,
     * midnight included (the day's change makes a state of its own, so the labels recompose).
     */
    val today: LocalDate,
    val loading: Boolean = true,
    /** Every page's row, by the pages' order. */
    val rows: List<GameRow> = emptyList(),
    val groups: Map<GameGroup, List<GameRow>> = emptyMap(),
    val pages: List<GamePage> = emptyList(),
    val unlinked: List<UnlinkedTitle> = emptyList(),
    /** The titles the owner hid, per console (13.4): the Hidden folds, each with Unhide. */
    val hidden: List<UnlinkedTitle> = emptyList(),
    /** Typed sessions whose page is gone (13.4): the fold that links them to another page. */
    val orphans: List<OrphanedPage> = emptyList(),
    /** Hides not yet read back from links.json ([UnlinkedTitle.foldKey]): those rows leave the fold at once. */
    val pendingHidden: Set<String> = emptySet(),
    /** links.json can't be read (13.4): the console folds wait, and the tab says so. */
    val linksUnreadable: Boolean = false,
    val running: RunningGame? = null,
    val runningTitle: String? = null,
    /** The running game's picture (13.4, P1): the wide card's cover. */
    val runningArt: GameArt? = null,
    /** The running session's "Still playing?", while unanswered (13.1). */
    val runningCheck: SessionCheck? = null,
    /** The game played last: the session picker's default (RFC-009 §4.3). */
    val lastPlayedPath: String? = null,
    val psn: PsnLine = PsnLine(configured = false, connected = false, status = null, expiresInDays = null),
    val xbox: XboxLine = XboxLine(configured = false, connected = false, status = null),
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** A game night would have started, but Do Not Disturb access isn't granted yet. */
    val askAccess: Boolean = false,
    /** The Personal mode as the owner named it in Modes settings (13.4): the game night dialog's word. */
    val personalLabel: String = ModeAppearance.defaultLabel(ContextMode.PERSONAL)
)

/** What an Undo on the tab's snackbar gives back (13.4). */
sealed interface GamesUndo {
    /** A title just hidden: back in its fold. */
    data class Unhide(val title: UnlinkedTitle) : GamesUndo

    /** A title just linked to [page]: its link back to [previous] (none: no link), unless it moved since. */
    data class Unlink(val title: UnlinkedTitle, val page: String, val previous: String?) : GamesUndo
}

/** A line for the tab's snackbar, with its Undo when it has one. */
data class GamesMessage(val text: String, val undo: GamesUndo? = null)

/**
 * The Games tab (RFC-009 §4.1; 13.3: the Xbox beside the PlayStation; 13.4: pictures, a grid, and
 * nothing read while the tab isn't on screen).
 */
@HiltViewModel
class GamesViewModel @Inject constructor(
    private val games: GamesRepository,
    private val controller: GameSessionController,
    private val account: PsnAccount,
    private val xboxAccount: XboxAccount,
    private val clock: AppClock,
    private val psnSync: PsnSync,
    private val xboxSync: XboxSync,
    private val modesRepository: ModesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GamesUiState(today = clock.today.value))
    val state: StateFlow<GamesUiState> = _state.asStateFlow()

    private val _messages = Channel<GamesMessage>(Channel.BUFFERED)

    /** The snackbar's lines, each shown once. */
    val messages: Flow<GamesMessage> = _messages.receiveAsFlow()

    /**
     * Keeps the tab's state current while the screen collects it (13.4, the audit's background
     * reloads): the screen calls this inside `repeatOnLifecycle(STARTED)`, so a tab left, or the app
     * sent to the background, reads nothing, and each return loads once. The first value of the
     * games files' ticks loads at once; a burst of writes (a sync's) loads once, [RELOAD_DEBOUNCE_MS]
     * after its last one. A day change, the running session, its question, both consoles' lines and
     * their syncs load too. Each load reads the disk, then builds the rows off the main thread; a
     * newer trigger cancels a load still running.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun watch() {
        val ticks = games.changes.withIndex()
            .debounce { tick -> if (tick.index == 0) 0L else RELOAD_DEBOUNCE_MS }
            .map { it.value }
        val session = combine(controller.running, controller.runningCheck) { running, check -> SessionInputs(running, check) }
        val consoles = combine(account.status, xboxAccount.status, psnSync.syncing, xboxSync.syncing) { psn, xbox, psnSyncing, xboxSyncing ->
            ConsoleInputs(psn, xbox, psnSyncing, xboxSyncing)
        }
        combine(ticks, session, consoles, clock.today) { tick, sessionInputs, consoleInputs, day -> Inputs(tick, sessionInputs, consoleInputs, day) }
            .distinctUntilChanged()
            .mapLatest { load(it) }
            .collect { loaded ->
                _state.update { current ->
                    if (loaded == null) {
                        current.copy(loading = false)
                    } else {
                        loaded.copy(
                            pendingHidden = GamesLibrary.stillPending(current.pendingHidden, loaded.unlinked),
                            askAccess = current.askAccess
                        )
                    }
                }
            }
    }

    /** One load: the disk first, then the pure build on Default; null when a read failed (the tab keeps what it shows). */
    private suspend fun load(inputs: Inputs): GamesUiState? = try {
        val pages = games.pages()
        val psn = games.snapshot()
        val xbox = games.xboxSnapshot()
        val links = games.linksRead()
        // Every month on disk (13.4): a game's typed hours count them all, not the last year's.
        val sessions = games.allSessions()
        val covers = games.coverFiles(pages)
        // A picture replaced at the same path loads again: its last change is part of its picture.
        val stamps: Map<String, Long> =
            if (covers.isEmpty()) emptyMap() else withContext(Dispatchers.IO) { covers.mapValues { (_, file) -> file.lastModified() } }
        // A build without a client never reads its sign-in.
        val tokens = if (account.configured) account.tokens() else null
        val xboxConnected = xboxAccount.configured && xboxAccount.isConnected()
        val today = clock.today.value
        val zone = ZoneId.systemDefault()
        val resolve = games.resolver(pages, links.links)
        val library = withContext(Dispatchers.Default) {
            GamesLibrary.build(
                pages = pages,
                psn = psn,
                xbox = xbox,
                links = links,
                sessions = sessions,
                resolve = resolve,
                today = today,
                zone = zone,
                covers = covers,
                coverStamps = stamps,
                running = inputs.session.running
            )
        }
        val readOnly = VaultWriteGate.readOnly
        val now = System.currentTimeMillis()
        GamesUiState(
            loading = false,
            today = today,
            rows = library.rows,
            groups = library.groups,
            pages = pages,
            unlinked = library.unlinked,
            hidden = library.hidden,
            orphans = library.orphans,
            linksUnreadable = !links.readable,
            running = inputs.session.running,
            runningTitle = library.runningTitle,
            runningArt = library.runningArt,
            runningCheck = inputs.session.check?.takeIf { it.askedAt != null },
            lastPlayedPath = library.lastPlayedPath,
            // 13.4: the statuses as they come; a Disconnect's null clears the line, it never keeps the last one.
            psn = PsnLine(
                configured = account.configured,
                connected = tokens != null,
                status = inputs.consoles.psn,
                expiresInDays = tokens?.let { t -> (t.refreshExpiresAt - now).coerceAtLeast(0) / DAY_MS },
                syncing = inputs.consoles.psnSyncing,
                readOnly = readOnly
            ),
            xbox = XboxLine(
                configured = xboxAccount.configured,
                connected = xboxConnected,
                status = inputs.consoles.xbox,
                syncing = inputs.consoles.xboxSyncing,
                readOnly = readOnly
            ),
            readOnly = readOnly,
            personalLabel = modesRepository.visibility.value.appearance.label(ContextMode.PERSONAL)
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The type and frames only: a message can hold a page's path.
        Log.w(TAG, "The Games tab couldn't load: " + AppScopeErrors.describe(e))
        null
    }

    /** Starts [page], [minutesAgo] earlier when the owner was already playing (13.1). */
    fun start(page: GamePage, minutesAgo: Int = 0) {
        viewModelScope.launch {
            val outcome = controller.start(page, startedMinutesAgo = minutesAgo)
            if (outcome == GameNightOutcome.NEEDS_ACCESS) {
                _state.update { it.copy(askAccess = true) }
            } else if (outcome == null && !VaultWriteGate.readOnly && _state.value.running != null) {
                // 13.4: the running session couldn't be saved, so it keeps running and nothing new starts.
                _messages.send(GamesMessage("The game being played couldn't be saved, so it keeps running"))
            }
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

    /**
     * [title] linked to [page], among its own console's links (13.3: Xbox's apart from PSN's): from
     * the page picker, or in one tap to the page it looks like (13.4). The snackbar says so, with an
     * Undo that puts the link back as it was: the app has no other way to undo a link.
     */
    fun link(title: UnlinkedTitle, page: GamePage) {
        viewModelScope.launch {
            var previous: String? = null
            val saved = games.updateLinks { links ->
                previous = links.consolePage(title.console, title.titleId)
                links.linkConsole(title.console, title.titleId, page.path)
            }
            if (saved == null) {
                _messages.send(GamesMessage(refusal("Couldn't link ${title.name}")))
            } else {
                // 13.4 review: a title taken off a page that's gone (renamed): the past week's notes
                // drop the old page's lines as the new page's come in.
                previous?.takeIf { old -> old != page.path && games.pages().none { it.path == old } }?.let { old ->
                    val today = LocalDate.now()
                    controller.refreshDays(
                        (1L..GamesRepository.PAST_DAYS).mapTo(HashSet()) { today.minusDays(it) },
                        replacingKeys = setOf(OrphanedSessions.keyOfPath(old))
                    )
                }
                _messages.send(GamesMessage("${title.name} linked to ${page.title}", GamesUndo.Unlink(title, page.path, previous)))
            }
        }
    }

    /**
     * [title] out of its console's fold (13.4): its row leaves at once, before the write; its link,
     * if any, is kept. Saved: "<name> hidden" with Undo; refused: the row comes back with the reason.
     */
    fun hide(title: UnlinkedTitle) {
        val key = title.foldKey
        _state.update { it.copy(pendingHidden = it.pendingHidden + key) }
        viewModelScope.launch {
            val saved = games.updateLinks { it.hideConsole(title.console, title.titleId) }
            if (saved != null) {
                _messages.send(GamesMessage("${title.name} hidden", GamesUndo.Unhide(title)))
            } else {
                _state.update { it.copy(pendingHidden = it.pendingHidden - key) }
                _messages.send(GamesMessage(refusal("Couldn't hide ${title.name}")))
            }
        }
    }

    /** [title] back in its console's fold (13.4: Undo, or the Hidden fold's Unhide); its link untouched. */
    fun unhide(title: UnlinkedTitle) {
        _state.update { it.copy(pendingHidden = it.pendingHidden - title.foldKey) }
        viewModelScope.launch {
            val saved = games.updateLinks { it.unhideConsole(title.console, title.titleId) }
            if (saved == null) _messages.send(GamesMessage(refusal("Couldn't bring back ${title.name}")))
        }
    }

    /** The snackbar's Undo. */
    fun undo(undo: GamesUndo) {
        when (undo) {
            is GamesUndo.Unhide -> unhide(undo.title)
            is GamesUndo.Unlink -> viewModelScope.launch {
                val saved = games.updateLinks { links ->
                    GamesLibrary.linkedBack(links, undo.title.console, undo.title.titleId, undo.page, undo.previous)
                }
                if (saved == null) _messages.send(GamesMessage(refusal("Couldn't undo the link of ${undo.title.name}")))
            }
        }
    }

    /** A page for [title] (13.4: the fold's "Create a page" for a title that looks like a page), linked with it. */
    fun createPage(title: UnlinkedTitle) {
        viewModelScope.launch {
            val page = games.createPageFor(title.console, title.titleId)
            _messages.send(GamesMessage(if (page != null) "Page created" else refusal("The page couldn't be created")))
        }
    }

    /**
     * The typed sessions of a page that's gone, moved to [page] (13.4, the audit's renamed pages):
     * every month on disk, then the notes of the days they fall on follow.
     */
    fun linkOrphan(orphan: OrphanedPage, page: GamePage) {
        viewModelScope.launch {
            val days = games.relinkManualSessions(orphan.oldPath, page.path)
            if (days == null) {
                _messages.send(GamesMessage("Couldn't link the sessions of ${orphan.name}"))
                return@launch
            }
            controller.refreshDays(days, replacingKeys = setOf(OrphanedSessions.keyOfPath(orphan.oldPath)))
            _messages.send(GamesMessage("Sessions of ${orphan.name} linked to ${page.title}"))
        }
    }

    /** Why links.json refused a change: the file can't be read (it is never rebuilt), else [fallback]. */
    private suspend fun refusal(fallback: String): String =
        if (!games.linksRead().readable) GamesLines.LINKS_UNREADABLE else fallback

    /** What a load follows: the games files' ticks, the session, the console lines and the day. */
    private data class Inputs(val tick: Int, val session: SessionInputs, val consoles: ConsoleInputs, val today: LocalDate)

    private data class SessionInputs(val running: RunningGame?, val check: SessionCheck?)

    private data class ConsoleInputs(val psn: PsnStatus?, val xbox: XboxStatus?, val psnSyncing: Boolean, val xboxSyncing: Boolean)

    private companion object {
        const val TAG = "GamesTab"

        /** A burst of games writes (a sync's) loads once, this long after its last one. */
        const val RELOAD_DEBOUNCE_MS = 300L

        const val DAY_MS = 86_400_000L
    }
}

/** What the tab shows, built from what one load read ([GamesLibrary.build]). */
internal data class GamesLibraryView(
    val rows: List<GameRow>,
    val groups: Map<GameGroup, List<GameRow>>,
    val unlinked: List<UnlinkedTitle>,
    val hidden: List<UnlinkedTitle>,
    val orphans: List<OrphanedPage>,
    val lastPlayedPath: String?,
    val runningTitle: String?,
    val runningArt: GameArt?
)

/** The library's rows and folds, from the pages, both consoles' snapshots and the sessions (pure, 13.3; 13.4: pictures, hidden titles, typed sessions of a page that's gone). */
internal object GamesLibrary {

    /**
     * How far back the game played last looks in the sessions: GameSessionController.defaultPage's
     * window, so the picker's "Played last" and the tile's game agree.
     */
    const val LAST_PLAYED_DAYS = 60L

    /**
     * Everything the tab lists, from one load's reads: a row per page (hours from [sessions], every
     * month of them), the groups, the console folds and the Hidden folds (none while links.json can't
     * be read: every title would look unclaimed), the typed sessions of a page that's gone, the game
     * played last, and the running game's title and picture (its page's, or its file name's once the
     * page is gone). [covers]: the owner's pictures by page path, [coverStamps] their last changes.
     */
    fun build(
        pages: List<GamePage>,
        psn: PsnSnapshot?,
        xbox: XboxSnapshot?,
        links: LinksRead,
        sessions: List<GameSessionRecord>,
        resolve: (GameSessionRecord) -> GamePage?,
        today: LocalDate,
        zone: ZoneId,
        covers: Map<String, File> = emptyMap(),
        coverStamps: Map<String, Long> = emptyMap(),
        running: RunningGame? = null
    ): GamesLibraryView {
        // One cell per path: the grid's keys are the paths.
        val library = pages.distinctBy { it.path }
        // Every month's sessions (13.4): GameDays.totals day by day, the same sums without reading
        // every session once per day of the history.
        val totals = HashMap<String, Int>()
        sessions.groupBy { it.date }.values.forEach { day ->
            GameDays.totals(day, resolve).forEach { (path, minutes) -> totals[path] = (totals[path] ?: 0) + minutes }
        }
        val playedDays = HashMap<String, MutableList<LocalDate>>()
        sessions.forEach { record ->
            val path = resolve(record)?.path ?: return@forEach
            val day = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: return@forEach
            playedDays.getOrPut(path) { ArrayList() }.add(day)
        }
        val rows = library.map { page ->
            val (psnTitles, xboxTitles) = GameArtRules.titlesOf(page, links.links, psn, xbox)
            row(
                page = page,
                psn = psnTitles,
                xbox = xboxTitles,
                sessionMinutes = totals[page.path] ?: 0,
                playedDays = playedDays[page.path].orEmpty(),
                zone = zone,
                ownCover = covers[page.path],
                ownCoverStamp = coverStamps[page.path] ?: 0L
            )
        }
        val byPath = rows.associateBy { it.page.path }
        val groups = GamePages.grouped(library).mapValues { (_, list) -> list.mapNotNull { byPath[it.path] } }
        val paths = library.mapTo(HashSet()) { it.path }
        val unlinked = if (links.readable) unlinked(psn, xbox, links.links, paths, zone, library) else emptyList()
        val hidden = if (links.readable) hidden(psn, xbox, links.links, zone) else emptyList()
        val recent = today.minusDays(LAST_PLAYED_DAYS).toString()..today.toString()
        val lastPlayed = LastPlayed.page(library, links.links, sessions.filter { it.date in recent }, psn, xbox, zone, resolve)
        val runningPage = running?.let { r -> library.firstOrNull { it.path == r.page } ?: OrphanedSessions.movedTo(r.page, library) }
        val runningTitle = running?.let { r -> runningPage?.title ?: fileTitle(r.page) }
        val runningArt = runningTitle?.let { title -> runningPage?.let { byPath[it.path]?.art } ?: GameArtRules.initialsOnly(title) }
        return GamesLibraryView(
            rows = rows,
            groups = groups,
            unlinked = unlinked,
            hidden = hidden,
            orphans = OrphanedSessions.of(sessions, library),
            lastPlayedPath = lastPlayed?.path,
            runningTitle = runningTitle,
            runningArt = runningArt
        )
    }

    /**
     * [page]'s row; [psn] and [xbox] are the page's linked titles of each console. The hours add both
     * consoles' totals once either measures the game, else `played_before` plus the sessions (XB3);
     * the picture is the owner's [ownCover] (13.4, P5), else PlayStation's, else Xbox's (P7), else the
     * initials, by [GameArtRules.of]. [ownCoverStamp]: the cover's last change, so a replaced one loads again.
     */
    fun row(
        page: GamePage,
        psn: List<PsnTitleState>,
        xbox: List<XboxTitleState>,
        sessionMinutes: Int,
        playedDays: List<LocalDate>,
        zone: ZoneId,
        ownCover: File? = null,
        ownCoverStamp: Long = 0L
    ): GameRow {
        val art = GameArtRules.of(page, ownCover, psn, xbox)
        return GameRow(
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
            art = if (art.ownFile != null && ownCoverStamp != 0L) art.copy(ownFileStamp = ownCoverStamp) else art
        )
    }

    /**
     * The titles no page claims, both consoles, the latest played first: no link, or a link to a page
     * that isn't in [pagePaths] any more (the owner deleted it: no new page comes, the fold offers it
     * again). Each console's hidden titles stay out; a PSN title needs play time, an Xbox one is
     * listed whatever its minutes (null hours while Xbox doesn't say). Each carries its picture, and
     * the page of [pages] it looks like ([GameNames.lookalike]).
     */
    fun unlinked(
        psn: PsnSnapshot?,
        xbox: XboxSnapshot?,
        links: GameLinks,
        pagePaths: Set<String>,
        zone: ZoneId,
        pages: List<GamePage> = emptyList()
    ): List<UnlinkedTitle> {
        fun claimed(console: GameConsole, titleId: String): Boolean = links.consolePage(console, titleId)?.let { it in pagePaths } ?: false
        val lookalikes = Lookalikes(pages)
        val fromPsn = psn?.titles.orEmpty().distinctBy { it.titleId }
            .filter { it.playSeconds > 0 && !claimed(GameConsole.PSN, it.titleId) && !links.isHidden(GameConsole.PSN, it.titleId) }
            .map { t -> instant(t.lastPlayed) to psnTitle(t, zone).copy(looksLike = lookalikes.of(t.name)) }
        val fromXbox = xbox?.titles.orEmpty().distinctBy { it.titleId }
            .filter { !claimed(GameConsole.XBOX, it.titleId) && !links.isHidden(GameConsole.XBOX, it.titleId) }
            .map { t -> instant(t.lastPlayed) to xboxTitle(t, zone).copy(looksLike = lookalikes.of(t.name)) }
        return newestFirst(fromPsn + fromXbox)
    }

    /**
     * The titles the owner hid (13.4: the Hidden folds, where Unhide brings one back), both consoles,
     * the latest played first. Only the titles a snapshot holds: a hidden id without one has no name.
     */
    fun hidden(psn: PsnSnapshot?, xbox: XboxSnapshot?, links: GameLinks, zone: ZoneId): List<UnlinkedTitle> {
        val fromPsn = psn?.titles.orEmpty().distinctBy { it.titleId }
            .filter { links.isHidden(GameConsole.PSN, it.titleId) }
            .map { t -> instant(t.lastPlayed) to psnTitle(t, zone) }
        val fromXbox = xbox?.titles.orEmpty().distinctBy { it.titleId }
            .filter { links.isHidden(GameConsole.XBOX, it.titleId) }
            .map { t -> instant(t.lastPlayed) to xboxTitle(t, zone) }
        return newestFirst(fromPsn + fromXbox)
    }

    /**
     * The hides still to read back (13.4): a pending title stays out of its fold until a load no
     * longer lists it there (hidden on disk, linked, or gone); then it is forgotten, so an Undo or an
     * Unhide brings it back with the next load.
     */
    fun stillPending(pending: Set<String>, unlinked: List<UnlinkedTitle>): Set<String> {
        if (pending.isEmpty()) return pending
        val listed = unlinked.mapTo(HashSet()) { it.foldKey }
        return pending.filterTo(LinkedHashSet()) { it in listed }
    }

    /**
     * [links] with [titleId]'s link of [console] put back to [previous] (none: no link), the Undo of
     * a link to [page]; unchanged when the title isn't linked to [page] any more (linked elsewhere
     * since: that change stays).
     */
    fun linkedBack(links: GameLinks, console: GameConsole, titleId: String, page: String, previous: String?): GameLinks {
        if (links.consolePage(console, titleId) != page) return links
        return when (console) {
            GameConsole.PSN -> links.copy(links = if (previous == null) links.links - titleId else links.links + (titleId to previous))
            GameConsole.XBOX -> links.copy(
                xbox = links.xbox.copy(links = if (previous == null) links.xbox.links - titleId else links.xbox.links + (titleId to previous))
            )
        }
    }

    private fun psnTitle(t: PsnTitleState, zone: ZoneId) = UnlinkedTitle(
        titleId = t.titleId,
        name = t.name,
        hours = t.playSeconds.takeIf { it > 0 }?.let { it / 3600.0 },
        lastPlayed = date(t.lastPlayed, zone),
        console = GameConsole.PSN,
        art = GameArtRules.forTitle(t.name, t.imageUrl, GameConsole.PSN)
    )

    private fun xboxTitle(t: XboxTitleState, zone: ZoneId) = UnlinkedTitle(
        titleId = t.titleId,
        name = t.name,
        hours = t.minutesPlayed?.let { it / 60.0 },
        lastPlayed = date(t.lastPlayed, zone),
        console = GameConsole.XBOX,
        art = GameArtRules.forTitle(t.name, t.imageUrl, GameConsole.XBOX)
    )

    /** Newest first; a title without a last played time goes last. */
    private fun newestFirst(titles: List<Pair<Instant?, UnlinkedTitle>>): List<UnlinkedTitle> =
        titles.sortedByDescending { it.first }.map { it.second }

    /**
     * [GameNames.lookalike], asked only of a title some page's name begins with, word for word: the
     * same test, with the pages' names read once per build instead of once per title.
     */
    private class Lookalikes(private val pages: List<GamePage>) {
        private val names = pages.flatMap { page -> page.names.map(GameNames::normalize) }.filter { it.isNotEmpty() }

        fun of(title: String): GamePage? {
            if (names.isEmpty()) return null
            val name = GameNames.normalize(title)
            if (name.isEmpty()) return null
            val prefix = "$name "
            return if (names.any { it.startsWith(prefix) }) GameNames.lookalike(title, pages) else null
        }
    }

    /** The earned share from the achievements file's total (kept in the snapshot), else titlehub's progress. */
    private fun achievementPercent(title: XboxTitleState): Int? {
        val total = title.totalAchievements?.takeIf { it > 0 }
        val earned = title.currentAchievements
        return if (total != null && earned != null) (earned * 100 / total).coerceIn(0, 100) else title.progress
    }

    /** A page's title from its file name, once the page is gone (the session notification's way). */
    private fun fileTitle(path: String): String = path.substringAfterLast('/').removeSuffix(".md").replace('_', ' ')

    private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun date(raw: String?, zone: ZoneId): LocalDate? = instant(raw)?.atZone(zone)?.toLocalDate()
}

/**
 * The PlayStation and Xbox lines' words (13.4, pure: the audit's status-lines-mislead). A read-only
 * device says the console syncs on the phone, a running sync says so, a sync says its day with its
 * time, and only a connected console whose last sync failed shows in the error colour.
 */
internal object GamesLines {

    /** The tab's notice, and a refused link, hide or page while links.json can't be read (13.4). */
    const val LINKS_UNREADABLE = "links.json can't be read: fix it on the desktop. Linking and hiding wait until then."

    fun psn(line: PsnLine, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val status = line.status
        return when {
            line.readOnly -> "PlayStation syncs on your phone"
            !line.connected -> "Connect PlayStation to bring play time and trophies"
            line.syncing -> "PSN: syncing…"
            status == null -> "PSN connected · first sync tonight at 23:40"
            status.ok -> "PSN ✓ ${syncedLabel(status.atMillis, today, zone)}" + (line.expiresInDays?.let { " · token expires in ${days(it)}" } ?: "")
            status.kind == PsnException.Kind.LINKS_UNREADABLE -> "PSN: links.json can't be read: fix it on the desktop"
            status.kind == PsnException.Kind.TOKEN_EXPIRED || status.kind == PsnException.Kind.NPSSO_REJECTED -> "PSN paused: paste a new token"
            status.kind == PsnException.Kind.CLIENT_REJECTED -> "PSN paused: PlayStation refused this build's client"
            else -> "PSN: last sync failed (${status.message.ifBlank { "unknown error" }})"
        }
    }

    fun xbox(line: XboxLine, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val status = line.status
        return when {
            line.readOnly -> "Xbox syncs on your phone"
            !line.connected -> "Connect Xbox to bring play time and achievements"
            line.syncing -> "Xbox: syncing…"
            status == null -> "Xbox connected · first sync tonight at 23:40"
            status.ok -> "Xbox ✓ ${syncedLabel(status.atMillis, today, zone)}"
            status.kind == XboxException.Kind.LINKS_UNREADABLE -> "Xbox: links.json can't be read: fix it on the desktop"
            status.needsSignIn -> "Xbox paused: sign in again"
            status.kind == XboxException.Kind.CLIENT_REJECTED -> "Xbox paused: Microsoft refused this build's app"
            status.kind == XboxException.Kind.ACCOUNT_REFUSED -> "Xbox paused: ${status.message.ifBlank { "Microsoft refused the account" }}"
            else -> "Xbox: last sync failed (${status.message.ifBlank { "unknown error" }})"
        }
    }

    /** The error colour: a connected console whose last sync failed, never a status kept from before a disconnect, nor while a sync runs. */
    fun failed(line: PsnLine): Boolean = line.connected && !line.readOnly && !line.syncing && line.status?.ok == false

    fun failed(line: XboxLine): Boolean = line.connected && !line.readOnly && !line.syncing && line.status?.ok == false

    private fun days(count: Long): String = if (count == 1L) "1 day" else "$count days"
}
