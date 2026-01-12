package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.model.LifeStatsLogic
import cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute
import cloud.wafflecommons.pixelbrainreader.data.model.RpgCharacter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LifeStatsRepository @Inject constructor(
    private val habitRepository: HabitRepository,
    private val taskRepository: TaskRepository,
    private val moodRepository: MoodRepository
) {

    fun getCharacterStats(): Flow<RpgCharacter> = flow {
        // 1. Fetch Data
        val today = LocalDate.now()
        val currentYear = today.year
        
        // Habits: Fetch Configs & Logs
        val allHabits = habitRepository.getHabitConfigs()
        val habitLogs = habitRepository.getLogsForYear(currentYear)
        
        // Filter IDs by Tag
        val vigIds = filterIdsByTag(allHabits, "(+VIG)")
        val mndIds = filterIdsByTag(allHabits, "(+MND)")
        val endIds = filterIdsByTag(allHabits, "(+END)")
        val intIds = filterIdsByTag(allHabits, "(+INT)")
        val fthIds = filterIdsByTag(allHabits, "(+FTH)")
        
        // Tasks (Sample last 7 days)
        var tasksCompleted = 0
        for (i in 0..6) {
            val date = today.minusDays(i.toLong())
            tasksCompleted += taskRepository.getScopedTasks(date).count { it.isCompleted }
        }

        // Mood (Last 7 days average)
        var totalMoodScore = 0
        var moodDays = 0
        for (i in 0..6) {
             try {
                val entry = moodRepository.getMood(today.minusDays(i.toLong()))
                if (entry != null) {
                    totalMoodScore += entry.score
                    moodDays++
                }
             } catch (e: Exception) { }
        }

        // 2. Calculate XP
        val vigXP = calculateHabitXP(habitLogs, vigIds)
        val mndXP = calculateHabitXP(habitLogs, mndIds)
        // ENDURANCE: Habits + Tasks
        val endXP = calculateHabitXP(habitLogs, endIds) + tasksCompleted
        val intXP = calculateHabitXP(habitLogs, intIds)
        val fthXP = calculateHabitXP(habitLogs, fthIds)

        // 3. Normalize to 0.0 - 1.0 (Max XP Benchmark = 50 for Alpha)
        val maxXP = 50.0f 

        val rawStats = mapOf(
            RpgAttribute.VIGOR to (vigXP / maxXP).coerceIn(0f, 1f),
            RpgAttribute.MIND to (mndXP / maxXP).coerceIn(0f, 1f),
            RpgAttribute.ENDURANCE to (endXP / maxXP).coerceIn(0f, 1f),
            RpgAttribute.INTELLIGENCE to (intXP / maxXP).coerceIn(0f, 1f),
            RpgAttribute.FAITH to (fthXP / maxXP).coerceIn(0f, 1f)
        )

        // 4. Determine Level
        val totalXP = vigXP + mndXP + endXP + intXP + fthXP
        val level = (totalXP / 10).toInt().coerceAtLeast(1)

        val character = RpgCharacter(
            stats = rawStats,
            level = level,
            className = LifeStatsLogic.determineClass(rawStats)
        )
        
        emit(character)
    }

    private fun filterIdsByTag(habits: List<cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig>, tag: String): List<String> {
        return habits
            .filter { it.description.contains(tag, ignoreCase = true) }
            .map { it.id }
    }

    private fun calculateHabitXP(logs: Map<String, List<cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry>>, validIds: List<String>): Int {
        var xp = 0
        validIds.forEach { id ->
            // Strict match: Only count logs if the ID is in our valid list
            xp += logs[id]?.size ?: 0
        }
        return xp
    }
}
