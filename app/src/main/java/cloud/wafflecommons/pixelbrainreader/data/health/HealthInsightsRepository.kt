package cloud.wafflecommons.pixelbrainreader.data.health

import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The days [HealthInsights] reads (L1, 12.20): the [HealthInsights.WINDOW_DAYS] days ending
 * yesterday (today is still being lived), from what the app already keeps. Nights from Health
 * Connect or the vault, steps from the day's metrics, mood as the day's average, tasks as the share
 * ticked in the day's note (the board when the day has none). Measured values only.
 */
@Singleton
class HealthInsightsRepository @Inject constructor(
    private val recoveryRepository: RecoveryRepository,
    private val healthMetricsRepository: HealthMetricsRepository,
    private val moodRepository: MoodRepository,
    private val noteRepository: NoteRepository,
    private val dashboardDao: DailyDashboardDao
) {
    suspend fun insights(today: LocalDate): List<HealthInsights.Insight> {
        val last = today.minusDays(1)
        return HealthInsights.of(days(last.minusDays(HealthInsights.WINDOW_DAYS.toLong() - 1), last))
    }

    /**
     * The measured days from [first] to [last], then the night after [last] alone: a day's steps
     * and mood pair with the night that ends the next day. Shared with the experiments (L2).
     */
    suspend fun days(first: LocalDate, last: LocalDate): List<HealthInsights.Day> = withContext(Dispatchers.IO) {
        val count = ChronoUnit.DAYS.between(first, last).toInt() + 1
        if (count <= 0) return@withContext emptyList()
        val nights = recoveryRepository.history(last.plusDays(1), count + 1).nights.associateBy { it.date }
        val steps = healthMetricsRepository.getMetricsHistoryFlow(last, count).first()
            .mapNotNull { metrics ->
                val date = runCatching { LocalDate.parse(metrics.date) }.getOrNull() ?: return@mapNotNull null
                metrics.steps.takeIf { it > 0 }?.let { date to it.toDouble() }
            }.toMap()
        val moods = moodRepository.getMoodsBetween(first, last).first()
            .groupBy { it.date }
            .mapValues { (_, entries) -> entries.map { it.score.toDouble() }.average() }
        (0..count).map { first.plusDays(it.toLong()) }.map { date ->
            val night = nights[date]
            HealthInsights.Day(
                date = date,
                sleepMinutes = night?.sleepMinutes?.takeIf { it > 0 }?.toDouble(),
                hrvMs = night?.hrvMs,
                steps = steps[date],
                mood = moods[date.toString()],
                tasksDone = if (date.isAfter(last)) null else tasksDone(date)
            )
        }
    }

    /** The share of the day's tasks ticked; null for a day without tasks. */
    private suspend fun tasksDone(day: LocalDate): Double? {
        val tasks = noteRepository.getNoteContent("10_Journal/$day.md")
            ?.let { DailyMarkdownParser.parse(day, it).tasks }
            ?: dashboardDao.getTasksSnapshot(day)
        if (tasks.isEmpty()) return null
        return tasks.count { it.isDone }.toDouble() / tasks.size
    }
}
