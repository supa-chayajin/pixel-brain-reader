package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the hand-offs of [DeskHandoffFormat]. Every change reads the file on disk first
 * (settled decision #8: the desktop may have ticked a line meanwhile) and requests the batched
 * push; the confidentiality guard checks it at commit like any note.
 */
@Singleton
class DeskHandoffRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val mutex = Mutex()

    suspend fun items(root: String): List<DeskHandoffItem> = withContext(Dispatchers.IO) {
        noteRepository.getNoteContent(DeskHandoffFormat.path(root))?.let(DeskHandoffFormat::items).orEmpty()
    }

    suspend fun add(root: String, projectTitle: String, text: String, at: LocalDateTime = LocalDateTime.now()) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val path = DeskHandoffFormat.path(root)
                val current = noteRepository.getNoteContent(path)
                noteRepository.saveNote(path, DeskHandoffFormat.add(current, projectTitle, at, text))
            }
        }
        pushScheduler.requestPush()
    }

    /** Ticks [item]; false when the line changed on disk meanwhile (nothing written). */
    suspend fun complete(root: String, item: DeskHandoffItem, today: LocalDate = LocalDate.now()): Boolean {
        val done = withContext(Dispatchers.IO) {
            mutex.withLock {
                val path = DeskHandoffFormat.path(root)
                val current = noteRepository.getNoteContent(path) ?: return@withLock false
                val updated = DeskHandoffFormat.complete(current, item.line, today) ?: return@withLock false
                noteRepository.saveNote(path, updated)
                true
            }
        }
        if (done) pushScheduler.requestPush()
        return done
    }
}
