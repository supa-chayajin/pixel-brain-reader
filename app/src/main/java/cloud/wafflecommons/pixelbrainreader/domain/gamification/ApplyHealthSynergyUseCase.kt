package cloud.wafflecommons.pixelbrainreader.domain.gamification

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import javax.inject.Inject

class ApplyHealthSynergyUseCase @Inject constructor(
    private val gamificationPreferences: GamificationPreferences,
    private val grantXpUseCase: GrantXpUseCase,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(date: LocalDate) = withContext(Dispatchers.IO) {
        val lastAppliedDate = gamificationPreferences.lastHealthSynergyAppliedDateFlow.first()
        if (lastAppliedDate == date.toString()) {
            return@withContext
        }

        val file = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
        if (!file.exists()) {
            return@withContext
        }

        val json = try {
            file.readText()
        } catch (e: Exception) {
            return@withContext
        }

        val metrics = try {
            Gson().fromJson(json, DailyHealthMetrics::class.java)
        } catch (e: Exception) {
            null
        } ?: return@withContext

        val stepTarget = gamificationPreferences.stepTargetFlow.first()
        val sleepMinMinutes = gamificationPreferences.sleepMinMinutesFlow.first()

        var applied = false

        if (metrics.steps >= stepTarget) {
            grantXpUseCase.executeCustom(
                attribute = cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.VIG, // ENDURANCE ~ VIGOR
                xpBase = 50.0,
                sourceId = "Health Synergy: Step Goal Reached"
            )
            applied = true
        }

        if (metrics.sleepDurationMinutes < sleepMinMinutes) {
            grantXpUseCase.executeCustom(
                attribute = cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.MND, // Energy Malus ~ MND
                xpBase = 0.0,
                sourceId = "Energy Malus (Lack of Sleep)"
            )
            applied = true
        }

        if (applied) {
            gamificationPreferences.setLastHealthSynergyAppliedDate(date.toString())
        }
    }
}
