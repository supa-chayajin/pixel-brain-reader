package cloud.wafflecommons.pixelbrainreader.domain.gamification

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

class AutomateHabitsUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(date: LocalDate) = withContext(Dispatchers.IO) {
        // Vault-rooted read. Post-JGit refactor, health metrics live at
        // `filesDir/vault/10_Journal/...`, not the pre-refactor `filesDir/10_Journal/...`.
        val json = fileRepository.readFile("10_Journal/data/health/metrics/$date.json")
        if (json.isNullOrBlank()) {
            Log.d("AutomateHabitsUseCase", "No health metrics found for $date")
            return@withContext
        }

        val metrics = try {
            Gson().fromJson(json, DailyHealthMetrics::class.java)
        } catch (e: Exception) {
            Log.e("AutomateHabitsUseCase", "Failed to parser health metrics", e)
            null
        } ?: return@withContext

        val allHabits = habitRepository.getHabitConfigs()
        val dayKey = date.dayOfWeek.name.take(3) 

        val activeHabitsForDay = allHabits.filter { habit -> 
            !habit.archived && 
            habit.frequency.contains(dayKey) && 
            !habit.autoSource.isNullOrBlank()
        }

        activeHabitsForDay.forEach { habit ->
            val extractedValue: Double = when (habit.autoSource) {
                // IMPORTANT: Health Connect metrics returns the ABSOLUTE daily total.
                // We MUST set this value, NOT add to it, to ensure Idempotency during repeated syncs.
                "health_connect_steps" -> metrics.steps.toDouble()
                "health_connect_sleep" -> metrics.sleepDurationMinutes / 60.0
                "health_connect_hydration" -> metrics.waterConsumedMl
                "health_connect_mindfulness" -> metrics.mindfulnessMinutes.toDouble()
                "health_connect_meditation" -> metrics.mindfulnessMinutes.toDouble()
                "health_connect_weight" -> metrics.weight
                
                // [FIX Phase 5] "Prise de Masse" Nutrition Mapping
                "health_connect_nutrition" -> metrics.caloriesConsumed
                
                else -> 0.0
            }

            if (extractedValue > 0) {
                // The underlying habitRepository.updateHabitValue strictly creates a NEW HabitLogEntry and replaces it by Date.
                // This guarantees `progress = extractedValue` and perfectly fixes the accumulation duplicate bug.
                Log.d("AutomateHabitsUseCase", "Automating habit ${habit.id} with absolute value $extractedValue, target: ${habit.targetValue}")
                habitRepository.updateHabitValue(date, habit.id, extractedValue)
            }
        }
    }
}
