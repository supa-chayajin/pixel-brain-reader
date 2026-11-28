package cloud.wafflecommons.pixelbrainreader.data.games

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxTimes
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.AssetRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.TimelineLine
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.di.AppScopeErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `links.json` as read (13.4): [readable] is false when the file exists but doesn't parse or
 * couldn't be read, and [links] is then empty. Nothing is linked, hidden or created from such a read.
 */
data class LinksRead(val links: GameLinks, val readable: Boolean)

/**
 * Games' vault side (RFC-009, 12.17): the game pages, the data files of [GameFiles], the day's
 * Timeline lines and a past day's edit (D11); since 13.3 also the Xbox files, a page's maps and the
 * pages the syncs create for played games; since 13.4 the owner's game pictures (P5). Every write
 * reads the disk first, moves an unreadable file aside rather than overwrite it (except links.json,
 * which is never rebuilt: the owner fixes it on the desktop), writes nothing over a file it couldn't
 * read, goes through the vault's writers (the read-only gate) and asks for a push. [changes] ticks
 * after each write so screens read again.
 */
@Singleton
class GamesRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val fileContentDao: FileContentDao,
    private val pushScheduler: VaultPushScheduler,
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val vaultDiscovery: VaultDiscoveryRepository,
    private val assets: AssetRepository
) : GameTimelineSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    // --- Pages ---------------------------------------------------------------------------

    /** Every game page: found through the notes' mirror, read from disk so a fresh edit shows at once. */
    suspend fun pages(): List<GamePage> = withContext(Dispatchers.IO) {
        val paths = try {
            fileContentDao.pathsContaining(GamePages.TEMPLATE_KEY + ":")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        paths.filter { it.endsWith(".md") }.mapNotNull { path -> read(path)?.let { GamePages.parse(path, it) } }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun page(path: String): GamePage? = withContext(Dispatchers.IO) { read(path)?.let { GamePages.parse(path, it) } }

    suspend fun pageContent(path: String): String? = withContext(Dispatchers.IO) { read(path) }

    /** The other notes of a page's folder and below (its builds, its answers). */
    suspend fun folderNotes(page: GamePage): List<String> = withContext(Dispatchers.IO) {
        if (page.folder.isEmpty()) return@withContext emptyList()
        try {
            fileContentDao.pathsUnder(page.folder + "/").filter { it != page.path && it.endsWith(".md") }.sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Writes [state] to the page's `game_state` (one frontmatter line, disk read first). */
    suspend fun setState(path: String, state: GameState, today: LocalDate = LocalDate.now()): Boolean =
        editPage(path) { GamePages.withState(it, state, today) }

    /**
     * Applies [change] to the page on disk; true when it was saved (or had nothing to change).
     * Only the page's own lines move: callers pass pure edits ([GamePages], [TrophySection],
     * [TrophyMarkers], [AchievementSection], [AchievementMarkers], [GameMapEdits]).
     */
    suspend fun editPage(path: String, change: (String) -> String): Boolean {
        if (VaultWriteGate.refuses()) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(path) ?: return@withLock false
                val next = change(current)
                if (next == current) return@withLock true
                save(path, next)
            }
        }
    }

    // --- Maps (13.3, ME1–ME3) --------------------------------------------------------------

    /** Adds [url] to the page's maps; the page is read from disk at write time and only `game_maps` (and `date_updated`) move. */
    suspend fun addMap(path: String, url: String, label: String?, today: LocalDate = LocalDate.now()): MapEditResult =
        editMaps(path) { GameMapEdits.add(it, url, label, today) }

    /** Gives the map at [oldUrl] the address [url] and the label [label], in its place. */
    suspend fun changeMap(path: String, oldUrl: String, url: String, label: String?, today: LocalDate = LocalDate.now()): MapEditResult =
        editMaps(path) { GameMapEdits.change(it, oldUrl, url, label, today) }

    suspend fun removeMap(path: String, url: String, today: LocalDate = LocalDate.now()): MapEditResult =
        editMaps(path) { GameMapEdits.remove(it, url, today) }

    /** Makes [url]'s map the page's main one. */
    suspend fun makeMainMap(path: String, url: String, today: LocalDate = LocalDate.now()): MapEditResult =
        editMaps(path) { GameMapEdits.makeMain(it, url, today) }

    private suspend fun editMaps(path: String, edit: (String) -> MapEdit): MapEditResult {
        if (VaultWriteGate.refuses()) return MapEditResult.READ_ONLY
        var result = MapEditResult.GONE
        val saved = editPage(path) { content ->
            when (val e = edit(content)) {
                is MapEdit.Saved -> {
                    result = MapEditResult.SAVED
                    e.content
                }
                is MapEdit.Refused -> {
                    result = e.reason
                    content
                }
            }
        }
        return if (saved) result else if (result == MapEditResult.SAVED) MapEditResult.FAILED else MapEditResult.GONE
    }

    // --- Pages for played games (13.3, XB4) ---------------------------------------------------

    /** The vault's Other template ([GamePageCreation.TEMPLATE_PATH]); null when it isn't there. */
    suspend fun otherTemplate(): String? = withContext(Dispatchers.IO) { read(GamePageCreation.TEMPLATE_PATH) }

    /** The notes under [folder] (the whole vault for `""`), from the notes' mirror; empty when it can't be read. */
    suspend fun notePathsUnder(folder: String): List<String> = withContext(Dispatchers.IO) {
        try {
            fileContentDao.pathsUnder(if (folder.isEmpty()) "" else "$folder/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Writes [pages], never over a file (each path is checked again under the lock), nor beside a
     * folder or file of its path that differs only by case (13.4: the desktops' clones can't hold
     * both); the pages written. Nothing on a read-only device.
     */
    suspend fun createPages(pages: List<NewGamePage>): List<GamePage> {
        if (pages.isEmpty() || VaultWriteGate.refuses()) return emptyList()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val written = pages.mapNotNull { page ->
                    val path = page.plan.path
                    val taken = try {
                        fileRepository.fileExists(path) || caseClash(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        true
                    }
                    if (taken || !save(path, page.content)) null else GamePages.parse(path, page.content)
                }
                Log.i(TAG, "Game pages created: ${written.size} of ${pages.size}")
                written
            }
        }
    }

    /**
     * The page for one console title (13.4: the fold's "Create a page", a held-back lookalike
     * included), linked with it; the page it is already linked to when that page is there. A link to
     * a page deleted since claims nothing (the Games tab's fold lists such a title as unclaimed):
     * the new page takes it over. Null when refused: a read-only device, links.json
     * unreadable, the title unknown, no layout, a page answering it, its path taken. Under the git
     * lock: never call it while holding that lock.
     */
    suspend fun createPageFor(console: GameConsole, titleId: String, today: LocalDate = LocalDate.now()): GamePage? {
        if (VaultWriteGate.refuses()) return null
        return gitSyncCoordinator.mutex.withLock {
            val read = linksRead()
            if (!read.readable) return@withLock null
            val pages = pages()
            val present = pages.mapTo(HashSet()) { it.path }
            read.links.consolePage(console, titleId)?.takeIf { it in present }?.let { linked ->
                return@withLock pages.firstOrNull { it.path == linked }
            }
            val candidate: PageCandidate
            val started: Instant?
            when (console) {
                GameConsole.PSN -> {
                    val title = snapshot()?.titles?.firstOrNull { it.titleId == titleId } ?: return@withLock null
                    candidate = PageCandidate(title.titleId, title.name, title.conceptTitleIds - title.titleId, instantOf(title.lastPlayed))
                    started = instantOf(title.firstPlayed)
                }
                GameConsole.XBOX -> {
                    // A title hidden on the Xbox profile too: the owner asked for its page.
                    val title = xboxSnapshot()?.titles?.firstOrNull { it.titleId == titleId } ?: return@withLock null
                    val lastPlayed = XboxTimes.instant(title.lastPlayed)
                    candidate = PageCandidate(title.titleId, title.name, emptyList(), lastPlayed)
                    val firstUnlock = achievementSet(titleId)?.achievements?.mapNotNull { XboxTimes.instant(it.earnedAt) }?.minOrNull()
                    started = listOfNotNull(firstUnlock, lastPlayed).minOrNull()
                }
            }
            val layout = GamePageCreation.layout(pages) ?: return@withLock null
            val plan = GamePageCreation.planOne(candidate, pages, notePathsUnder(layout.root), console) ?: return@withLock null
            val page = GamePageCreation.render(plan, otherTemplate(), started?.let { dayOf(it) }, today)
            // 13.4 review: once the lock is held, the page and its link are written together, whatever
            // cancels the caller (a Back that clears the tab): a page left without its link stays unlinked.
            withContext(NonCancellable) {
                val created = createPages(listOf(page)).firstOrNull() ?: return@withContext null
                updateLinks { links ->
                    plan.titleIds.fold(links) { acc, id ->
                        // Unlinked, or linked to a page that's gone: the new page's; a hidden id stays as it is.
                        val claimed = acc.consolePage(console, id)?.let { it in present } ?: false
                        if (!claimed && !acc.isHidden(console, id)) acc.linkConsole(console, id, plan.path) else acc
                    }
                }
                created
            }
        }
    }

    /** True when a folder or file on [path]'s way exists with another case only. Blocking: call from IO. */
    private fun caseClash(path: String): Boolean {
        var dir = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val names = fileRepository.getLocalFile(dir).list() ?: return false
            if (names.any { it != part && it.equals(part, ignoreCase = true) }) return true
            if (part !in names) return false
            dir = if (dir.isEmpty()) part else "$dir/$part"
        }
        return false
    }

    // --- The owner's game pictures (13.4, P5) ---------------------------------------------------

    /**
     * Each page's own picture (page path → file), for the pages whose `game_cover` resolves to a
     * picture of the vault, through Room's file index (read once per call, never a walk of the disk)
     * and inside the vault (a link out of it never resolves).
     */
    suspend fun coverFiles(pages: List<GamePage>): Map<String, File> {
        val withCover = pages.filter { it.cover != null }
        if (withCover.isEmpty()) return emptyMap()
        val index = try {
            vaultDiscovery.observeAttachmentIndex().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        return withContext(Dispatchers.IO) {
            withCover.mapNotNull { page ->
                val path = GameCovers.resolve(page, index) ?: return@mapNotNull null
                assets.safeFile(path)?.let { page.path to it }
            }.toMap()
        }
    }

    /**
     * The owner's picture for the page [path] (P5): [jpeg] written as the page's own file (replaced
     * when there, the same game's picture), then `game_cover` pointed at it. Never stopped half-way:
     * a page left mid-way never keeps a file without its key. Refused on a read-only device.
     */
    suspend fun setCover(path: String, jpeg: ByteArray, today: LocalDate = LocalDate.now()): CoverEditResult {
        if (VaultWriteGate.refuses()) return CoverEditResult.READ_ONLY
        return withContext(NonCancellable + Dispatchers.IO) {
            val page = read(path)?.let { GamePages.parse(path, it) } ?: return@withContext CoverEditResult.GONE
            if (!assets.writeAt(GameCovers.ownPath(page), jpeg)) return@withContext CoverEditResult.FAILED
            pushScheduler.requestPush()
            _changes.value++
            if (editPage(path) { GameCovers.withCover(it, GameCovers.fileName(page.key), today) }) {
                CoverEditResult.SAVED
            } else {
                CoverEditResult.FAILED
            }
        }
    }

    /**
     * The page's picture taken away: `game_cover` removed, and the file deleted only when the key
     * named the app's own file (a picture the owner named by hand stays). Refused on a read-only device.
     */
    suspend fun removeCover(path: String, today: LocalDate = LocalDate.now()): CoverEditResult {
        if (VaultWriteGate.refuses()) return CoverEditResult.READ_ONLY
        return withContext(NonCancellable + Dispatchers.IO) {
            val content = read(path) ?: return@withContext CoverEditResult.GONE
            val page = GamePages.parse(path, content) ?: return@withContext CoverEditResult.GONE
            if (FrontmatterLines.get(content, GameCovers.KEY) == null) return@withContext CoverEditResult.NOTHING_TO_REMOVE
            if (!editPage(path) { GameCovers.withoutCover(it, today) }) return@withContext CoverEditResult.FAILED
            val target = page.cover
            if (target != null && GameCovers.namesOwnFile(page, target) && assets.deleteAt(GameCovers.ownPath(page))) {
                pushScheduler.requestPush()
                _changes.value++
            }
            CoverEditResult.SAVED
        }
    }

    // --- Data files ------------------------------------------------------------------------

    suspend fun snapshot(): PsnSnapshot? = load(GameFiles.SNAPSHOT, PsnSnapshot.serializer()).value

    suspend fun saveSnapshot(snapshot: PsnSnapshot): Boolean = replace(GameFiles.SNAPSHOT, PsnSnapshot.serializer(), snapshot)

    /** links.json and whether it could be read (13.4): an unreadable one gives empty links, never acted on. */
    suspend fun linksRead(): LinksRead {
        val loaded = load(GameFiles.LINKS, GameLinks.serializer())
        return LinksRead(loaded.value ?: GameLinks(), readable = !loaded.unreadable && !loaded.failed)
    }

    /** The links, for display: empty when links.json can't be read ([linksRead] says so). */
    suspend fun links(): GameLinks = linksRead().links

    /**
     * Applies [change] to links.json on disk; null when refused. An unreadable links.json (or one
     * that couldn't be read) is never rebuilt from empty links nor moved aside (13.4): the owner's
     * links and hides would go, and the syncs would create pages for every title linked by hand.
     */
    suspend fun updateLinks(change: (GameLinks) -> GameLinks): GameLinks? =
        update(GameFiles.LINKS, GameLinks.serializer(), GameLinks(), rebuildUnreadable = false, change = change)

    /** The owner's settings for Games; the app never writes them. */
    suspend fun config(): GamesConfig = load(GameFiles.CONFIG, GamesConfig.serializer()).value ?: GamesConfig()

    suspend fun trophySet(set: String): TrophySetFile? = load(GameFiles.trophies(set), TrophySetFile.serializer()).value

    suspend fun saveTrophySet(file: TrophySetFile): Boolean = replace(GameFiles.trophies(file.set), TrophySetFile.serializer(), file)

    /** The last Xbox sync (13.3); null before the first one or when the file doesn't parse. */
    suspend fun xboxSnapshot(): XboxSnapshot? = load(GameFiles.XBOX_SNAPSHOT, XboxSnapshot.serializer()).value

    suspend fun saveXboxSnapshot(snapshot: XboxSnapshot): Boolean = replace(GameFiles.XBOX_SNAPSHOT, XboxSnapshot.serializer(), snapshot)

    suspend fun achievementSet(titleId: String): AchievementSetFile? =
        load(GameFiles.achievements(titleId), AchievementSetFile.serializer()).value

    suspend fun saveAchievementSet(file: AchievementSetFile): Boolean =
        replace(GameFiles.achievements(file.titleId), AchievementSetFile.serializer(), file)

    suspend fun sessions(month: YearMonth): List<GameSessionRecord> =
        load(GameFiles.sessions(month.toString()), GameSessionsMonth.serializer()).value?.sessions.orEmpty()

    /** The sessions from [from] to [to], both included. */
    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<GameSessionRecord> {
        val first = YearMonth.from(from)
        val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
        val range = from.toString()..to.toString()
        return months.flatMap { sessions(it) }.filter { it.date in range }
    }

    /** The months the sessions folder holds (its `yyyy-MM.json` files), sorted; read only. */
    suspend fun sessionMonths(): List<YearMonth> = withContext(Dispatchers.IO) {
        val names = try {
            fileRepository.getLocalFile("${GameFiles.FOLDER}/sessions").listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        names.mapNotNull { name -> MONTH_FILE.matchEntire(name)?.groupValues?.get(1)?.let { runCatching { YearMonth.parse(it) }.getOrNull() } }
            .distinct()
            .sorted()
    }

    /** Every session of every month on disk (13.4: a game's typed hours count them all, not the last year's). */
    suspend fun allSessions(): List<GameSessionRecord> = sessionMonths().flatMap { sessions(it) }

    /**
     * Adds [records] to their months by [GameSessionMerge]'s rule (13.4: a typed session replaces
     * its own page's of the same minute, never another game's); the dates they fall on and the dates
     * of the records they replaced, or null when a month couldn't be saved (a read-only device, a disk
     * failure, an unreadable file kept aside). A console record replayed from the same previous sync
     * may land in another month than its first copy: that copy is taken out (and its date returned).
     */
    suspend fun addSessions(records: List<GameSessionRecord>): Set<LocalDate>? {
        if (records.isEmpty()) return emptySet()
        if (VaultWriteGate.refuses()) return null
        val changed = mutableSetOf<LocalDate>()
        records.groupBy { it.date.take(7) }.forEach { (month, batch) ->
            var replaced = emptySet<String>()
            update(GameFiles.sessions(month), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
                val merged = GameSessionMerge.merge(file.sessions, batch)
                replaced = merged.replacedDates
                GameSessionsMonth(merged.sessions)
            } ?: return null
            batch.mapNotNullTo(changed) { dateOf(it.date) }
            replaced.mapNotNullTo(changed) { dateOf(it) }
        }
        // A replay's first copy in another month, from the previous sync's month (a day before, for
        // a session that spanned it) to the latest a record can be dated.
        val thisMonth = YearMonth.now(zone)
        records.filter { it.measured }.forEach { record ->
            val baseline = GameSessionMerge.baselineOf(record.id) ?: return@forEach
            val own = record.date.take(7)
            val recordMonth = runCatching { YearMonth.parse(own) }.getOrNull() ?: return@forEach
            var month = runCatching { YearMonth.from(baseline.atZone(zone).toLocalDate().minusDays(1)) }.getOrNull() ?: return@forEach
            val last = maxOf(recordMonth, thisMonth)
            while (!month.isAfter(last)) {
                if (month.toString() != own && sessions(month).any { it.id == record.id }) {
                    var removed = emptyList<GameSessionRecord>()
                    update(GameFiles.sessions(month.toString()), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
                        removed = file.sessions.filter { it.id == record.id }
                        if (removed.isEmpty()) file else GameSessionsMonth(file.sessions.filterNot { it.id == record.id })
                    } ?: return null
                    removed.mapNotNullTo(changed) { dateOf(it.date) }
                }
                month = month.plusMonths(1)
            }
        }
        return changed
    }

    /**
     * Replaces [old] by [new] in their month (13.1: a session fixed by hand; both on the same day),
     * in one write: [old] found by its id and its page (13.4), then [new] merged by
     * [GameSessionMerge]'s rule; false when [old] isn't there any more or the month couldn't be saved.
     */
    suspend fun replaceSession(old: GameSessionRecord, new: GameSessionRecord): Boolean {
        if (VaultWriteGate.refuses()) return false
        if (old.date.take(7) != new.date.take(7)) return false
        var found = false
        val saved = update(GameFiles.sessions(old.date.take(7)), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
            found = file.sessions.any { it.id == old.id && it.page == old.page }
            if (!found) return@update file
            val kept = file.sessions.filterNot { it.id == old.id && it.page == old.page }
            GameSessionsMonth(GameSessionMerge.merge(kept, listOf(new)).sessions)
        }
        return found && saved != null
    }

    /**
     * The typed and phone-started sessions of [oldPath] moved to [newPath] (13.4: a page renamed or
     * deleted on the desktop, linked again from the fold), in every month, disk first; the dates
     * touched, or null when a month couldn't be saved.
     */
    suspend fun relinkManualSessions(oldPath: String, newPath: String): Set<LocalDate>? {
        if (VaultWriteGate.refuses()) return null
        if (oldPath == newPath) return emptySet()
        fun GameSessionRecord.isOf(path: String) = source == GameSessionRecord.SOURCE_MANUAL && page == path
        val touched = mutableSetOf<LocalDate>()
        for (month in sessionMonths()) {
            if (sessions(month).none { it.isOf(oldPath) }) continue
            var dates = emptySet<String>()
            update(GameFiles.sessions(month.toString()), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
                val moved = file.sessions.filter { it.isOf(oldPath) }
                if (moved.isEmpty()) return@update file
                val merged = GameSessionMerge.merge(file.sessions - moved.toSet(), moved.map { it.copy(page = newPath) })
                dates = moved.mapTo(HashSet()) { it.date } + merged.replacedDates
                GameSessionsMonth(merged.sessions)
            } ?: return null
            dates.mapNotNullTo(touched) { dateOf(it) }
        }
        return touched
    }

    /** Writes [hours] to the page's `played_before` (one frontmatter line, disk read first). */
    suspend fun setPlayedBefore(path: String, hours: Double?, today: LocalDate = LocalDate.now()): Boolean =
        editPage(path) { GamePages.withPlayedBefore(it, hours, today) }

    /** Removes the session [id] of [date]; true when it was there. */
    suspend fun removeSession(id: String, date: LocalDate): Boolean {
        if (VaultWriteGate.refuses()) return false
        var removed = false
        update(GameFiles.sessions(YearMonth.from(date).toString()), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
            val kept = file.sessions.filterNot { it.id == id }
            removed = kept.size != file.sessions.size
            GameSessionsMonth(kept)
        }
        return removed
    }

    // --- The Timeline (RFC-009 §4.3) -------------------------------------------------------

    /**
     * A session's page: its own (manual), or its console title's link ([GameLinks.pageFor]: PSN's or
     * Xbox's). A manual session whose page is gone follows the one page with its file name (13.4: a
     * page moved to another folder on the desktop).
     */
    fun resolver(pages: List<GamePage>, links: GameLinks): (GameSessionRecord) -> GamePage? {
        val byPath = pages.associateBy { it.path }
        val byKey = pages.groupBy { it.key }
        return { record ->
            links.pageFor(record)?.let(byPath::get)
                ?: record.page?.takeIf { record.source == GameSessionRecord.SOURCE_MANUAL && it !in byPath }
                    ?.let { byKey[OrphanedSessions.keyOfPath(it)]?.singleOrNull() }
        }
    }

    suspend fun dayLines(date: LocalDate): List<GameDayLine> {
        val sessions = sessions(YearMonth.from(date)).filter { it.date == date.toString() }
        if (sessions.isEmpty()) return emptyList()
        return GameDays.lines(date, sessions, resolver(pages(), links()))
    }

    override suspend fun linesFor(date: LocalDate): List<TimelineLine> = GameTimeline.timelineLines(dayLines(date))

    /**
     * A past day's note gets its game lines (D11): only its marked Timeline lines change, and the note
     * is never re-burned (Room's board for that day may be older than a desktop edit). Up to
     * [PAST_DAYS] back; a day without a note keeps its sessions in the data file only. Refused (13.4)
     * while the notes' mirror holds no game page, while links.json can't be read, or when the day has
     * sessions and none of them finds its page: re-deriving the lines then would strip them. A line
     * whose page is gone stays, as the day's history, unless its key is in [replacingKeys] (13.4
     * review): a gone page whose sessions were just linked to another one, so its old line would
     * double the new page's.
     */
    suspend fun applyPastDay(date: LocalDate, today: LocalDate = LocalDate.now(), replacingKeys: Set<String> = emptySet()): Boolean {
        if (!date.isBefore(today) || date.isBefore(today.minusDays(PAST_DAYS))) return false
        val pages = pages()
        if (pages.isEmpty()) {
            Log.i(TAG, "Past day left as it is: no game page in the notes' mirror")
            return false
        }
        val read = linksRead()
        if (!read.readable) {
            Log.i(TAG, "Past day left as it is: links.json can't be read")
            return false
        }
        val sessions = sessions(YearMonth.from(date)).filter { it.date == date.toString() }
        val resolve = resolver(pages, read.links)
        if (sessions.isNotEmpty() && sessions.none { resolve(it) != null }) {
            Log.i(TAG, "Past day left as it is: none of its ${sessions.size} sessions finds its page")
            return false
        }
        val lines = GameTimeline.timelineLines(GameDays.lines(date, sessions, resolve))
        val known = pages.mapTo(HashSet()) { it.key } + replacingKeys
        return editPage(journalPath(date)) { GameTimeline.applyToNote(it, lines, knownKeys = known) }
    }

    // --- History (RFC-009 §4.3) --------------------------------------------------------------

    /**
     * Timeline lines of earlier daily notes that name a game, read as past sessions without a
     * length. Derived lines are left out (the sessions files have them). Nothing is rewritten.
     */
    suspend fun history(pages: List<GamePage>): List<GameHistoryEntry> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext emptyList()
        val notes = try {
            fileContentDao.contentsUnder(JOURNAL + "/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        notes.mapNotNull { note ->
            val day = DAILY.matchEntire(note.path)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            day?.let { it to note.content }
        }.flatMap { (day, content) -> GameHistory.entries(day, content, pages) }
            .sortedWith(compareByDescending<GameHistoryEntry> { it.date }.thenByDescending { it.time })
    }

    // --- File plumbing -----------------------------------------------------------------------

    /** A data file as read (13.4): missing (no file, or a blank one), its text, or a read that failed while it exists. */
    private sealed interface Raw {
        data object Missing : Raw
        data class Text(val value: String) : Raw
        data object Failed : Raw
    }

    /** [unreadable]: the file exists and doesn't parse; [failed]: it exists and couldn't be read (the file itself may be fine). */
    private class Loaded<T>(val value: T?, val unreadable: Boolean, val failed: Boolean)

    private suspend fun read(path: String): String? = try {
        fileRepository.readFile(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readRaw(path: String): Raw {
        val text = read(path)
        if (text != null) return if (text.isBlank()) Raw.Missing else Raw.Text(text)
        val exists = try {
            fileRepository.fileExists(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        return if (exists) Raw.Failed else Raw.Missing
    }

    private suspend fun <T> load(path: String, serializer: KSerializer<T>): Loaded<T> = withContext(Dispatchers.IO) {
        when (val raw = readRaw(path)) {
            Raw.Missing -> Loaded(null, unreadable = false, failed = false)
            Raw.Failed -> {
                Log.w(TAG, "$path couldn't be read")
                Loaded(null, unreadable = false, failed = true)
            }
            is Raw.Text -> try {
                Loaded(json.decodeFromString(serializer, raw.value), unreadable = false, failed = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The type only: a parser's message quotes the file, and the file names games and pages.
                Log.w(TAG, "$path doesn't parse: ${e.javaClass.simpleName}")
                Loaded(null, unreadable = true, failed = false)
            }
        }
    }

    /**
     * Applies [change] to the file on disk (or [default] when absent); null when nothing could be
     * saved. A file that couldn't be read is never written; an unreadable one is moved aside first,
     * or (without [rebuildUnreadable]: links.json) left as it is and the change refused.
     */
    private suspend fun <T> update(
        path: String,
        serializer: KSerializer<T>,
        default: T,
        rebuildUnreadable: Boolean = true,
        change: (T) -> T
    ): T? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved = load(path, serializer)
                if (saved.failed) {
                    Log.w(TAG, "$path wasn't written: it couldn't be read")
                    return@withLock null
                }
                if (saved.unreadable && !rebuildUnreadable) {
                    Log.w(TAG, "$path wasn't written: it doesn't parse")
                    return@withLock null
                }
                val current = saved.value ?: default
                val next = change(current)
                if (next == current && saved.value != null) return@withLock current
                if (saved.unreadable && fileRepository.moveAsideCorrupt(path) == null) {
                    Log.e(TAG, "Unreadable $path could not be kept aside; not overwriting it")
                    return@withLock null
                }
                if (save(path, json.encodeToString(serializer, next))) next else null
            }
        }
    }

    /** Writes [value] whole (a file only the sync produces, rebuilt from PSN's answer). */
    private suspend fun <T> replace(path: String, serializer: KSerializer<T>, value: T): Boolean =
        update(path, serializer, value) { value } != null

    private suspend fun save(path: String, content: String): Boolean = try {
        fileRepository.saveFileLocally(path, content)
        pushScheduler.requestPush()
        _changes.value++
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No path: a page's path names a game, and the exception's message holds it.
        Log.e(TAG, "A games file couldn't be saved: " + AppScopeErrors.describe(e))
        false
    }

    private fun dateOf(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()

    private fun instantOf(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun dayOf(instant: Instant): LocalDate? = runCatching { instant.atZone(zone).toLocalDate() }.getOrNull()

    companion object {
        private const val TAG = "Games"
        private const val JOURNAL = "10_Journal"
        private val DAILY = Regex("^10_Journal/(\\d{4}-\\d{2}-\\d{2})\\.md$")
        private val MONTH_FILE = Regex("^([0-9]{4}-[0-9]{2})\\.json$")
        const val PAST_DAYS = 7L

        /** The status line while links.json can't be read (13.4): the syncs keep the play and link nothing. */
        const val LINKS_UNREADABLE_MESSAGE = "links.json can't be read: fix it on the desktop. Play time was kept."

        fun journalPath(date: LocalDate): String = "$JOURNAL/$date.md"

        val zone: ZoneId get() = ZoneId.systemDefault()
    }
}
