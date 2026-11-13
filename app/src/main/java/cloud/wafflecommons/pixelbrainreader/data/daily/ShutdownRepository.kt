package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** What the shutdown banner shows on [day]: the tasks still open, the inbox, and whether the win can be logged. */
data class ShutdownState(val day: LocalDate, val tasks: List<DailyTaskEntity>, val inboxCount: Int, val canLogWin: Boolean)

/**
 * The shutdown ritual (D8): reads the board, moves a task where the owner says (today's note is
 * never rewritten), logs the win and closes the day. Never on a read-only device (#18).
 */
@Singleton
class ShutdownRepository @Inject constructor(
    private val dashboardDao: DailyDashboardDao,
    private val dashboardRepository: DailyDashboardRepository,
    private val taskRepository: TaskRepository,
    private val fileRepository: FileRepository,
    private val fileContentDao: FileContentDao,
    private val missionLogRepository: MissionLogRepository,
    private val modesRepository: ModesRepository,
    private val preferences: UserPreferencesRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val somedayMutex = Mutex()

    /** The banner at [now], or null when it isn't due (not a work day, too early, closed, read-only). */
    suspend fun state(now: LocalDateTime): ShutdownState? = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext null
        val today = now.toLocalDate()
        val closed = preferences.shutdownClosedDay.first()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (!Shutdown.isDue(modesRepository.config.value, now, closed)) return@withContext null
        val handled = preferences.rolloverHandled.first()
        ShutdownState(
            day = today,
            tasks = Shutdown.openTasks(dashboardDao.getTasksSnapshot(today), today, handled),
            inboxCount = Shutdown.inboxCount(fileContentDao.pathsUnder(VaultSchema.INBOX_FOLDER + "/")),
            canLogWin = !modesRepository.currentVisibility().workContextHidden
        )
    }

    /** When the banner starts next today, or null when it already has (or today isn't a work day). */
    fun nextStart(now: LocalDateTime): LocalDateTime? {
        val config = modesRepository.config.value
        if (java.time.DayOfWeek.from(now).value !in config.workDays) return null
        return Shutdown.startOf(config, now.toLocalDate()).takeIf { it.isAfter(now) }
    }

    /** Onto tomorrow's board now (priority kept, time dropped); tomorrow's rollover leaves it alone. */
    suspend fun toTomorrow(today: LocalDate, task: DailyTaskEntity) {
        if (VaultWriteGate.refuses()) return
        taskRepository.addTask(task.label, today.plusDays(1), priority = task.priority)
        preferences.markRolloverHandled(listOf(Shutdown.handledKey(today, task.label)), today)
    }

    /** Appended to the someday note, read from disk first, never rewritten. */
    suspend fun toSomeday(today: LocalDate, task: DailyTaskEntity) {
        if (VaultWriteGate.refuses()) return
        withContext(Dispatchers.IO) {
            somedayMutex.withLock {
                val current = fileRepository.readFile(Shutdown.SOMEDAY_PATH)
                val next = Shutdown.withSomeday(current, task.label, today)
                if (next != current) {
                    fileRepository.saveFileLocally(Shutdown.SOMEDAY_PATH, next)
                    pushScheduler.requestPush()
                }
            }
        }
        preferences.markRolloverHandled(listOf(Shutdown.handledKey(today, task.label)), today)
    }

    /** Let go: tomorrow's rollover doesn't offer it. */
    suspend fun letGo(today: LocalDate, task: DailyTaskEntity) {
        if (VaultWriteGate.refuses()) return
        preferences.markRolloverHandled(listOf(Shutdown.handledKey(today, task.label)), today)
    }

    /** The win of the day, as a mission-log fact tagged [Shutdown.WIN_TAG]. */
    suspend fun logWin(text: String, now: LocalDateTime = LocalDateTime.now()) {
        val win = text.replace('\n', ' ').trim()
        if (win.isEmpty() || VaultWriteGate.refuses()) return
        missionLogRepository.add(LogEntry(at = now, kind = LogKind.FACT, text = win, tags = listOf(Shutdown.WIN_TAG)))
    }

    /**
     * Burns today's note at once and remembers the day as closed; the evening mode to offer, or
     * null when it is already on (never switched here: the owner taps).
     */
    suspend fun closeDay(today: LocalDate): ContextMode? {
        if (VaultWriteGate.refuses()) return null
        dashboardRepository.burnToDisk(today)
        preferences.setShutdownClosedDay(today)
        // The evening's own mode: with the custody calendar on, Off when the child is home tonight.
        val config = modesRepository.config.value
        val evening = ModeResolver.outsideHoursModeAt(config, today.atTime(config.endTime()))
        return evening.takeIf { modesRepository.currentVisibility().mode != it }
    }

    suspend fun switchTo(mode: ContextMode) = modesRepository.setManualMode(mode)
}
