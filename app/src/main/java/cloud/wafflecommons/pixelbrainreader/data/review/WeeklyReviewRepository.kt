package cloud.wafflecommons.pixelbrainreader.data.review

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceRepository
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogFormat
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ReportPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly review note (D6), written once after Sunday's nightly burn by [DailyExportWorker]
 * (owner, 2026-11-11). Every fact is read on its own: one that can't be read leaves its section
 * out instead of failing the note. Never on a read-only device (#18).
 */
@Singleton
class WeeklyReviewRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val fileRepository: FileRepository,
    private val draftWriter: DraftWriter,
    private val dashboardDao: DailyDashboardDao,
    private val habitRepository: HabitRepository,
    private val moodRepository: MoodRepository,
    private val healthMetricsRepository: HealthMetricsRepository,
    private val choreRepository: ChoreRepository,
    private val gamificationRepository: GamificationRepository,
    private val gitFileAges: GitFileAges,
    private val fileContentDao: FileContentDao,
    private val evidenceRepository: EvidenceRepository,
    private val objectiveRepository: ObjectiveRepository,
    private val loadRecoveryRepository: LoadRecoveryRepository,
    private val missionLogRepository: MissionLogRepository,
    private val modesRepository: ModesRepository,
    private val userPrefs: UserPreferencesRepository
) {

    /**
     * Writes the most recent due week's review unless it was written before (on this phone, or
     * already on disk); returns the path written, or null when there was nothing to write.
     */
    suspend fun writeDue(now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): String? =
        withContext(Dispatchers.IO) {
            if (VaultWriteGate.readOnly) return@withContext null
            val week = ReviewWeek.lastDue(now)
            if (userPrefs.weeklyReviewWritten.first() == week.key) return@withContext null
            if (fileRepository.fileExists(week.path)) {
                userPrefs.setWeeklyReviewWritten(week.key)
                return@withContext null
            }
            val content = WeeklyReviewFormat.render(week, facts(week, now.toLocalDate(), zone))
            val written = draftWriter.write(week.path, content)
            userPrefs.setWeeklyReviewWritten(week.key)
            Log.i(TAG, "Weekly review written for ${week.key}")
            written
        }

    /** The week's measured facts; also the brief "Ask Coach about this week" hands to Chat. */
    suspend fun facts(week: ReviewWeek, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): WeeklyFacts =
        withContext(Dispatchers.IO) {
            // A worker can start the process: read the modes from disk so work folders are known
            // before anything is listed as personal.
            optional(Unit) { modesRepository.reload() }
            val workFolders = modesRepository.config.value.workFolders
            val isWork: (String) -> Boolean = { path -> workFolders.any { path == it || path.startsWith("$it/") } }
            val tasks = optional(0 to 0) { WeeklyActivity.tasks(week.days.map { tasksOf(it) }) }
            val gamification = optional(null) { gamificationRepository.getStateSnapshot() }
            WeeklyFacts(
                habits = optional(emptyList()) {
                    val logs = habitRepository.getLogsBetweenFlow(week.monday, week.sunday).first().values.flatten()
                    WeeklyActivity.habits(habitRepository.getHabitConfigs(), logs, week)
                },
                mood = optional(null) {
                    val before = week.previous()
                    WeeklyActivity.mood(
                        moodRepository.getMoodsBetween(week.monday, week.sunday).first(),
                        moodRepository.getMoodsBetween(before.monday, before.sunday).first()
                    )
                },
                health = optional(null) {
                    WeeklyActivity.health(week.days.map { day ->
                        val metrics = healthMetricsRepository.getMetrics(day)
                        DayHealth(metrics?.sleepDurationMinutes ?: 0L, metrics?.steps ?: 0L)
                    })
                },
                tasksDone = tasks.first,
                tasksTotal = tasks.second,
                choresDone = optional(emptyList()) { WeeklyActivity.chores(choreRepository.getAllChoresStream().first(), week) },
                xpGained = gamification?.let { WeeklyActivity.xp(it.history, week, zone) },
                level = gamification?.profile?.level,
                notes = optional(null) { notesOf(week, zone, isWork) },
                work = optional(null) { workOf(week, today) },
                lastWeekPlan = optional(emptyList()) {
                    noteRepository.getNoteContent(week.previous().path)?.let(WeeklyReviewFormat::planLines).orEmpty()
                }
            )
        }

    /** The day's tasks from its burned note, or from the board when the day has no note yet. */
    private suspend fun tasksOf(day: LocalDate): List<DailyTaskEntity> {
        val content = noteRepository.getNoteContent("10_Journal/$day.md")
        return if (content != null) DailyMarkdownParser.parse(day, content).tasks else dashboardDao.getTasksSnapshot(day)
    }

    /** Git's created and edited notes, kept only while they still exist and aren't a mirror. */
    private suspend fun notesOf(week: ReviewWeek, zone: ZoneId, isWork: (String) -> Boolean): NoteActivity {
        val history = gitFileAges.history()
        val kept = WeeklyActivity.candidates(history, week, zone).filter { path ->
            val content = fileContentDao.getContent(path)
            content != null && !MirrorNotes.isGenerated(path, content)
        }.toSet()
        return WeeklyActivity.notes(history, week, zone, isWork, keep = { it in kept })
    }

    /** Counts only (owner, 2026-11-11): the evidence report's own figures for the week. */
    private suspend fun workOf(week: ReviewWeek, today: LocalDate): WorkWeek {
        val figures = evidenceRepository.figures(ReportPeriod.Week(week.monday), EvidenceScope.WORK)
        val workFolders = modesRepository.config.value.workFolders
        val stalled = optional(0) {
            objectiveRepository.objectives(today)
                .filter { EvidenceScope.WORK.includes(it.note.path, workFolders) }
                .count { it.stale(today) }
        }
        val overload = optional(null) { loadRecoveryRepository.report(force = true).overload }
        val settings = missionLogRepository.reloadSettings()
        val logLinks = if (settings.encrypted) {
            emptyList()
        } else {
            figures.entries.map { YearMonth.from(it.at) }.distinct().sorted()
                .map { WeeklyActivity.link(MissionLogFormat.pathFor(settings, it)) }
        }
        return WeeklyActivity.work(figures.entries, figures.objectivesProgress.size, stalled, figures.meetings.size, overload, logLinks)
    }

    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A part of the weekly review couldn't be read: ${e.javaClass.simpleName}")
        fallback
    }

    private companion object {
        const val TAG = "WeeklyReview"
    }
}
