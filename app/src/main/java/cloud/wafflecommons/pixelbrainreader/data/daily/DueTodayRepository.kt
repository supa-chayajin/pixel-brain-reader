package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what [DueToday] lists: the notes once from the `file_contents` mirror (no private note is in
 * it), the chores and rooms from Room. The mode's [Visibility] applies: work notes hide outside
 * Work mode. Never writes.
 */
@Singleton
class DueTodayRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val choreRepository: ChoreRepository
) {
    suspend fun due(today: LocalDate, visibility: Visibility): List<DueItem> = withContext(Dispatchers.IO) {
        val notes = fileContentDao.contentsUnder("")
            .filter { it.path.endsWith(".md") && visibility.isVisible(it.path) }
            .map { WorkNote.from(it.path, it.content) }
        val rooms = choreRepository.getAllRoomsStream().first().associate { it.id to it.name }
        val chores = choreRepository.getAllChoresStream().first()
        DueToday.sorted(DueToday.fromNotes(notes, today) + DueToday.fromChores(chores, rooms, today))
    }
}
