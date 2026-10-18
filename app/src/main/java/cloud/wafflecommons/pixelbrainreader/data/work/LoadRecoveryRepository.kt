package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryMath
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryStatus
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Eight weeks of work load against recovery, and this week's overload signal (#7). */
data class LoadRecoveryReport(
    val comparisons: List<LoadComparison>,
    val workDays: Int,
    val loggedDays: Int,
    val overload: OverloadSignal?,
    val needsHealthPermission: Boolean
)

@Singleton
class LoadRecoveryRepository @Inject constructor(
    private val meetingRepository: MeetingRepository,
    private val missionLogRepository: MissionLogRepository,
    private val recoveryRepository: RecoveryRepository,
    private val moodRepository: MoodRepository,
    private val modesRepository: ModesRepository
) {

    @Volatile
    private var cached: Pair<Long, LoadRecoveryReport>? = null

    /** The report, reused for [CACHE_MS] (it reads weeks of Health Connect and the vault) unless [force]. */
    suspend fun report(force: Boolean = false): LoadRecoveryReport {
        val now = System.currentTimeMillis()
        cached?.let { (at, report) -> if (!force && now - at < CACHE_MS) return report }
        return compute().also { cached = now to it }
    }

    private suspend fun compute(today: LocalDate = LocalDate.now(), weeks: Int = WEEKS): LoadRecoveryReport = withContext(Dispatchers.IO) {
        val from = today.minusWeeks(weeks.toLong())
        val workDays = modesRepository.config.value.workDays.toSet()
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .filter { it.dayOfWeek.value in workDays }
            .toList()
        val log = try {
            missionLogRepository.entriesBetween(from, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val load = LoadRecovery.dailyLoad(days, meetingRepository.meetings(), log)
        val history = recoveryRepository.history(today, weeks * 7 + 30)
        val nights = history.nights.associateBy { it.date }
        val moods = moodRepository.getMoodsBetween(from, today).first()
            .groupBy { it.date }
            .mapNotNull { (date, entries) -> runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to entries.map { e -> e.score.toDouble() }.average() } }
            .toMap()

        // Compare only finished days: today's load and tonight's sleep aren't in yet.
        val finished = load.filter { it.date < today }
        val comparisons = LoadRecovery.compare(finished, nights, moods)

        val lastWeek = load.filter { it.date > today.minusDays(7) }.sumOf { it.minutes }
        val previousWeeks = (1 until weeks).map { back ->
            val end = today.minusDays(7L * back)
            load.filter { it.date <= end && it.date > end.minusDays(7) }.sumOf { it.minutes }
        }.filter { it > 0 }
        val overload = LoadRecovery.overload(lastWeek, previousWeeks, recoveryReasons(history.nights, moods, today))

        LoadRecoveryReport(
            comparisons = comparisons,
            workDays = finished.size,
            loggedDays = finished.count { it.minutes > 0 || it.requests > 0 || it.meetings > 0 },
            overload = overload,
            needsHealthPermission = history.needsPermission
        )
    }

    /** What says recovery is below the user's own norm this week; empty when nothing does. */
    private fun recoveryReasons(nights: List<cloud.wafflecommons.pixelbrainreader.data.health.RecoveryDay>, moods: Map<LocalDate, Double>, today: LocalDate): List<String> {
        val reasons = mutableListOf<String>()
        if (RecoveryMath.readout(nights, today).status == RecoveryStatus.STRAINED) reasons += "Last night's recovery is below your norm"
        val week = nights.filter { it.date > today.minusDays(7) && !it.date.isAfter(today) }
        val before = nights.filter { it.date <= today.minusDays(7) && it.date > today.minusDays(7 + RecoveryMath.BASELINE_NIGHTS.toLong()) }
        val hrvBase = RecoveryMath.baseline(before.mapNotNull { it.hrvMs })
        val hrvWeek = week.mapNotNull { it.hrvMs }
        if (hrvBase != null && hrvWeek.size >= 3 && hrvWeek.average() < hrvBase.mean - hrvBase.sd) reasons += "HRV over the last nights is below your norm"
        val sleepBase = RecoveryMath.baseline(before.mapNotNull { it.sleepMinutes?.toDouble() })
        val sleepWeek = week.mapNotNull { it.sleepMinutes?.toDouble() }
        if (sleepBase != null && sleepWeek.size >= 3 && sleepWeek.average() < sleepBase.mean - sleepBase.sd) reasons += "Less sleep than usual this week"
        val moodWeek = moods.filterKeys { it > today.minusDays(7) }.values
        val moodBefore = moods.filterKeys { it <= today.minusDays(7) }.values
        if (moodWeek.size >= 3 && moodBefore.size >= 7 && moodWeek.average() <= moodBefore.average() - MOOD_DROP) reasons += "Mood lower than usual this week"
        return reasons
    }

    private companion object {
        const val WEEKS = 8
        const val CACHE_MS = 30 * 60_000L
        const val MOOD_DROP = 0.5
    }
}
