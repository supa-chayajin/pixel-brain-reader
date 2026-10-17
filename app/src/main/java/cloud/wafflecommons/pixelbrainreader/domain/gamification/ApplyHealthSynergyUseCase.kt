package cloud.wafflecommons.pixelbrainreader.domain.gamification

import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Health bonuses: step goal (+50 VIG), sleep goal (+25 MND) and high mood × activity synergy
 * (+30 MND), each granted AT MOST ONCE per day.
 *
 * This runs on every foreground refresh (via AutomateHabitsUseCase), so the day's metrics grow
 * across many calls. Each bonus is therefore tracked on its own: one shared "applied today" flag
 * let the morning sleep bonus lock out the step bonus earned later the same day.
 */
class ApplyHealthSynergyUseCase @Inject constructor(
    private val gamificationPreferences: GamificationPreferences,
    private val grantXpUseCase: GrantXpUseCase,
    private val moodRepository: MoodRepository,
    private val healthMetricsRepository: HealthMetricsRepository
) {
    suspend operator fun invoke(date: LocalDate): Int = withContext(Dispatchers.IO) {
        val metrics = healthMetricsRepository.getMetrics(date) ?: return@withContext 0

        // Synergy: today's mood average (or yesterday's while today has none) × activity.
        val moods = moodRepository.getMoodFlow().first()
        val todayMoods = moods.filter { it.date == date.toString() }
        val moodSource = todayMoods.ifEmpty { moods.filter { it.date == date.minusDays(1).toString() } }
        val avgMood = if (moodSource.isNotEmpty()) moodSource.map { it.score }.average().toFloat() else 0f
        val synergy = if (avgMood > 0f) {
            ((avgMood / MAX_MOOD) * (metrics.activeMinutes.toFloat() / ACTIVE_MINUTES_GOAL)) * 100f
        } else {
            0f // no mood yet
        }
        val finalSynergy = synergy.toInt().coerceIn(0, 100)

        val record = gamificationPreferences.lastHealthSynergyAppliedDateFlow.first()
        val alreadyGranted = HealthBonusLedger.grantedOn(record, date)
        val stepTarget = gamificationPreferences.stepTargetFlow.first()
        val sleepMinMinutes = gamificationPreferences.sleepMinMinutesFlow.first()

        val newlyGranted = mutableSetOf<String>()
        if (HealthBonusLedger.STEPS !in alreadyGranted && metrics.steps >= stepTarget) {
            grantXpUseCase.executeCustom(Attribute.VIG, xpBase = 50.0, sourceId = "Health Synergy: Step Goal Reached")
            newlyGranted += HealthBonusLedger.STEPS
        }
        if (HealthBonusLedger.SLEEP !in alreadyGranted && sleepMinMinutes > 0 &&
            metrics.sleepDurationMinutes >= sleepMinMinutes
        ) {
            grantXpUseCase.executeCustom(Attribute.MND, xpBase = 25.0, sourceId = "Health Synergy: Sleep Goal Reached")
            newlyGranted += HealthBonusLedger.SLEEP
        }
        if (HealthBonusLedger.SYNERGY !in alreadyGranted && finalSynergy >= HIGH_SYNERGY) {
            grantXpUseCase.executeCustom(Attribute.MND, xpBase = 30.0, sourceId = "High Health/Mood Synergy")
            newlyGranted += HealthBonusLedger.SYNERGY
        }
        if (newlyGranted.isNotEmpty()) {
            gamificationPreferences.setLastHealthSynergyAppliedDate(
                HealthBonusLedger.record(date, alreadyGranted + newlyGranted)
            )
        }

        finalSynergy
    }

    private companion object {
        const val MAX_MOOD = 5f
        const val ACTIVE_MINUTES_GOAL = 30f
        const val HIGH_SYNERGY = 80
    }
}

/**
 * Which health bonuses were already granted on a day, stored in the existing preference as
 * `2026-10-17:steps,sleep`. A legacy value holding only a date (the old all-or-nothing flag) means
 * every bonus was handled that day — never grant them twice across an app update.
 */
internal object HealthBonusLedger {
    const val STEPS = "steps"
    const val SLEEP = "sleep"
    const val SYNERGY = "synergy"
    private val ALL = setOf(STEPS, SLEEP, SYNERGY)

    fun grantedOn(record: String, date: LocalDate): Set<String> {
        val day = record.substringBefore(':')
        if (day != date.toString()) return emptySet()
        if (!record.contains(':')) return ALL
        return record.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun record(date: LocalDate, granted: Set<String>): String =
        "$date:" + granted.sorted().joinToString(",")
}
