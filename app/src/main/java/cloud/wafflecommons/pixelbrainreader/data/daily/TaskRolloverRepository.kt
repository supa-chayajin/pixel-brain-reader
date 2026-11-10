package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [TaskRollover] gets its days and keeps what was handled. Reads the earlier day's burned note
 * (the day's last word, desktop edits included), else its board in Room; writes only today's board
 * (Bring) and the handled keys in the phone's preferences. Never a file.
 */
@Singleton
class TaskRolloverRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val dashboardDao: DailyDashboardDao,
    private val taskRepository: TaskRepository,
    private val preferences: UserPreferencesRepository
) {
    val handled: Flow<Set<String>> = preferences.rolloverHandled

    /** The most recent day before [today] with a note or a board (≤ [TaskRollover.LOOKBACK_DAYS] back), with its tasks. */
    suspend fun earlierDay(today: LocalDate): Pair<LocalDate, List<DailyTaskEntity>>? = withContext(Dispatchers.IO) {
        for (back in 1..TaskRollover.LOOKBACK_DAYS) {
            val day = today.minusDays(back)
            val content = noteRepository.getNoteContent("10_Journal/${day.format(DateTimeFormatter.ISO_DATE)}.md")
            if (content != null) return@withContext day to DailyMarkdownParser.parse(day, content).tasks
            if (dashboardDao.getDashboard(day) != null) return@withContext day to dashboardDao.getTasksSnapshot(day)
        }
        null
    }

    /** Adds [tasks] to [today]'s board, without their old time, and remembers them as handled. */
    suspend fun bring(today: LocalDate, tasks: List<RolloverTask>) {
        if (tasks.isEmpty() || VaultWriteGate.refuses()) return
        tasks.forEach { taskRepository.addTask(it.label, today, scheduledTime = null, priority = it.priority) }
        preferences.markRolloverHandled(tasks.map { it.key }, today)
    }

    /** Forgets [tasks] on this phone: they are not offered again. */
    suspend fun dismiss(today: LocalDate, tasks: List<RolloverTask>) {
        if (tasks.isEmpty() || VaultWriteGate.refuses()) return
        preferences.markRolloverHandled(tasks.map { it.key }, today)
    }
}
