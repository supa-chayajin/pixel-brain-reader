package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Boards (#4): which folders are boards, their cards, and the surgical card edits. */
@Singleton
class BoardRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler,
    private val draftWriter: DraftWriter
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()

    private val _config = MutableStateFlow(BoardsConfig())
    val config: StateFlow<BoardsConfig> = _config.asStateFlow()

    private val _changes = MutableStateFlow(0L)

    /** Bumped after every card edit. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) {
        readConfig()?.let { _config.value = it }
    }

    suspend fun addBoard(folder: String) = updateConfig { c -> if (folder in c.boards) c else c.copy(boards = (c.boards + folder).sorted()) }

    suspend fun removeBoard(folder: String) = updateConfig { c -> c.copy(boards = c.boards - folder) }

    /** The folder's notes as cards (sub-folders included), most recently updated first. */
    suspend fun cards(folder: String): List<BoardCard> = withContext(Dispatchers.IO) {
        fileContentDao.contentsUnder(folder.trimEnd('/') + "/")
            .filter { BoardFormat.isCard(it.path) }
            .map { BoardCard.of(WorkNote.from(it.path, it.content)) }
            .sortedWith(compareByDescending<BoardCard> { it.note.updated }.thenBy { it.note.title })
    }

    suspend fun move(card: BoardCard, column: StatusColumn) = edit(card.note.path) { content, today ->
        BoardFormat.move(card.note.path, content, column, today)
    }

    suspend fun setDetails(card: BoardCard, nextStep: String?, blockers: String?, targetDate: LocalDate?) = edit(card.note.path) { content, today ->
        BoardFormat.setDetails(card.note.path, content, nextStep, blockers, targetDate, today)
    }

    /** Writes the board's French synthesis to the inbox; returns its path. */
    suspend fun exportSynthesis(folder: String): String = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        draftWriter.write(BoardFormat.synthesisPath(folder, today), BoardFormat.synthesis(folder, cards(folder), today))
    }

    /** Reads the note from disk (never the mirror, which may lag), applies [change], writes it back. */
    private suspend fun edit(path: String, change: (String, LocalDate) -> String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val content = fileRepository.readFile(path) ?: throw IllegalStateException("The note is no longer there.")
            val updated = change(content, LocalDate.now())
            if (updated != content) {
                fileRepository.saveFileLocally(path, updated)
                pushScheduler.requestPush()
            }
        }
        _changes.update { it + 1 }
    }

    private suspend fun updateConfig(transform: (BoardsConfig) -> BoardsConfig) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val base = readConfig() ?: run {
                if (fileRepository.fileExists(CONFIG_PATH)) fileRepository.moveAsideCorrupt(CONFIG_PATH)
                _config.value
            }
            val updated = transform(base)
            fileRepository.saveFileLocally(CONFIG_PATH, json.encodeToString(BoardsConfig.serializer(), updated) + "\n")
            _config.value = updated
            pushScheduler.requestPush()
        }
    }

    private suspend fun readConfig(): BoardsConfig? {
        val raw = fileRepository.readFile(CONFIG_PATH) ?: return null
        return runCatching { json.decodeFromString(BoardsConfig.serializer(), raw) }.getOrNull()
    }

    companion object {
        const val CONFIG_PATH = "10_Journal/system/boards.json"
    }
}
