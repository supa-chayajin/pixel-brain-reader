package cloud.wafflecommons.pixelbrainreader.data.games

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.TimelineLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Games' vault side (RFC-009, 12.17): the game pages, the data files of [GameFiles], the day's
 * Timeline lines and a past day's edit (D11). Every write reads the disk first, moves an
 * unreadable file aside rather than overwrite it, goes through the vault's writers (the read-only
 * gate) and asks for a push. [changes] ticks after each write so screens read again.
 */
@Singleton
class GamesRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val fileContentDao: FileContentDao,
    private val pushScheduler: VaultPushScheduler
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
     * Only the page's own lines move: callers pass pure edits ([GamePages], [TrophySection], [TrophyMarkers]).
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

    // --- Data files ------------------------------------------------------------------------

    suspend fun snapshot(): PsnSnapshot? = load(GameFiles.SNAPSHOT, PsnSnapshot.serializer()).value

    suspend fun saveSnapshot(snapshot: PsnSnapshot): Boolean = replace(GameFiles.SNAPSHOT, PsnSnapshot.serializer(), snapshot)

    suspend fun links(): GameLinks = load(GameFiles.LINKS, GameLinks.serializer()).value ?: GameLinks()

    suspend fun updateLinks(change: (GameLinks) -> GameLinks): GameLinks? =
        update(GameFiles.LINKS, GameLinks.serializer(), GameLinks(), change)

    /** The owner's settings for Games; the app never writes them. */
    suspend fun config(): GamesConfig = load(GameFiles.CONFIG, GamesConfig.serializer()).value ?: GamesConfig()

    suspend fun trophySet(set: String): TrophySetFile? = load(GameFiles.trophies(set), TrophySetFile.serializer()).value

    suspend fun saveTrophySet(file: TrophySetFile): Boolean = replace(GameFiles.trophies(file.set), TrophySetFile.serializer(), file)

    suspend fun sessions(month: YearMonth): List<GameSessionRecord> =
        load(GameFiles.sessions(month.toString()), GameSessionsMonth.serializer()).value?.sessions.orEmpty()

    /** The sessions from [from] to [to], both included. */
    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<GameSessionRecord> {
        val first = YearMonth.from(from)
        val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
        val range = from.toString()..to.toString()
        return months.flatMap { sessions(it) }.filter { it.date in range }
    }

    /**
     * Adds [records] to their months, a known id replaced; the dates they fall on, or null when a
     * month couldn't be saved (a read-only device, a disk failure, an unreadable file kept aside).
     */
    suspend fun addSessions(records: List<GameSessionRecord>): Set<LocalDate>? {
        if (records.isEmpty()) return emptySet()
        if (VaultWriteGate.refuses()) return null
        val changed = mutableSetOf<LocalDate>()
        records.groupBy { it.date.take(7) }.forEach { (month, batch) ->
            update(GameFiles.sessions(month), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
                val byId = file.sessions.associateBy { it.id }.toMutableMap()
                batch.forEach { byId[it.id] = it }
                GameSessionsMonth(byId.values.sortedWith(compareBy({ it.date }, { it.start ?: "" }, { it.id })))
            } ?: return null
            batch.mapNotNullTo(changed) { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        }
        return changed
    }

    /**
     * Replaces [old] by [new] in their month (13.1: a session fixed by hand; both on the same day),
     * in one write; false when [old] isn't there any more or the month couldn't be saved.
     */
    suspend fun replaceSession(old: GameSessionRecord, new: GameSessionRecord): Boolean {
        if (VaultWriteGate.refuses()) return false
        if (old.date.take(7) != new.date.take(7)) return false
        var found = false
        val saved = update(GameFiles.sessions(old.date.take(7)), GameSessionsMonth.serializer(), GameSessionsMonth()) { file ->
            found = file.sessions.any { it.id == old.id }
            if (!found) return@update file
            val kept = file.sessions.filterNot { it.id == old.id || it.id == new.id } + new
            GameSessionsMonth(kept.sortedWith(compareBy({ it.date }, { it.start ?: "" }, { it.id })))
        }
        return found && saved != null
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

    /** A session's page: its own (manual), or its PSN title's link. */
    fun resolver(pages: List<GamePage>, links: GameLinks): (GameSessionRecord) -> GamePage? {
        val byPath = pages.associateBy { it.path }
        return { record -> (record.page ?: record.titleId?.let(links::pageOf))?.let(byPath::get) }
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
     * [PAST_DAYS] back; a day without a note keeps its sessions in the data file only.
     */
    suspend fun applyPastDay(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        if (!date.isBefore(today) || date.isBefore(today.minusDays(PAST_DAYS))) return false
        val lines = linesFor(date)
        return editPage(journalPath(date)) { GameTimeline.applyToNote(it, lines) }
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

    private class Loaded<T>(val value: T?, val unreadable: Boolean)

    private suspend fun read(path: String): String? = try {
        fileRepository.readFile(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> load(path: String, serializer: KSerializer<T>): Loaded<T> = withContext(Dispatchers.IO) {
        val raw = read(path)
        if (raw.isNullOrBlank()) return@withContext Loaded(null, unreadable = false)
        try {
            Loaded(json.decodeFromString(serializer, raw), unreadable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$path doesn't parse: ${e.message}")
            Loaded(null, unreadable = true)
        }
    }

    /** Applies [change] to the file on disk (or [default] when absent); null when nothing could be saved. */
    private suspend fun <T> update(path: String, serializer: KSerializer<T>, default: T, change: (T) -> T): T? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved = load(path, serializer)
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
        Log.e(TAG, "Failed to save $path", e)
        false
    }

    companion object {
        private const val TAG = "Games"
        private const val JOURNAL = "10_Journal"
        private val DAILY = Regex("^10_Journal/(\\d{4}-\\d{2}-\\d{2})\\.md$")
        const val PAST_DAYS = 7L

        fun journalPath(date: LocalDate): String = "$JOURNAL/$date.md"

        val zone: ZoneId get() = ZoneId.systemDefault()
    }
}
