package cloud.wafflecommons.pixelbrainreader.domain.gamification

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

class AutomateHabitsUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(date: LocalDate) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
        if (!file.exists()) {
            Log.d("AutomateHabitsUseCase", "No health metrics found for $date")
            return@withContext
        }

        val json = try { file.readText() } catch (e: Exception) { return@withContext }
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
                "health_connect_steps" -> metrics.steps.toDouble()
                "health_connect_sleep" -> metrics.sleepDurationMinutes / 60.0
                "health_connect_hydration" -> metrics.waterConsumedMl
                "health_connect_nutrition" -> metrics.caloriesConsumed
                "health_connect_mindfulness" -> metrics.mindfulnessMinutes.toDouble()
                "health_connect_weight" -> metrics.weight
                else -> 0.0
            }

            if (extractedValue > 0) {
                // The underlying habitRepository.updateHabitValue handles checking extractedValue >= targetValue
                // if the type is MEASURABLE. We simply pass the raw aggregated progress here.
                Log.d("AutomateHabitsUseCase", "Automating habit ${habit.id} with value $extractedValue, target: ${habit.targetValue}")
                habitRepository.updateHabitValue(date, habit.id, extractedValue)
            }
        }
    }
}
