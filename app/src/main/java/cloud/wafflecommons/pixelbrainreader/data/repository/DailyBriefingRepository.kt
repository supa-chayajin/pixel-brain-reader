package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.ai.OracleGenerator
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyBriefingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBriefingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class DailyBriefingModel(
    val briefing: String,
    val oracleInsight: String?
)

@Singleton
class DailyBriefingRepository @Inject constructor(
    private val dao: DailyBriefingDao,
    private val briefingGenerator: BriefingGenerator,
    private val oracleGenerator: OracleGenerator,
    private val weatherRepository: WeatherRepository
) {
    // A Mutex to ensure only one generation runs at a time for a given date check
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun getBriefingForDate(date: LocalDate): DailyBriefingModel = withContext(Dispatchers.IO) {
        val dateStr = date.toString()

        // 1. Fast Path: Check Cached Data (No Lock)
        dao.getBriefing(dateStr)?.let { hit ->
            if (isValid(hit)) {
                return@withContext DailyBriefingModel(
                    briefing = hit.briefingContent,
                    oracleInsight = hit.oracleContent
                )
            }
        }

        // 2. Slow Path: Acquire Lock (Double-Checked Locking)
        mutex.withLock {
            // Re-fetch to see if another thread finished while we were waiting
            val cached = dao.getBriefing(dateStr)
            if (cached != null && isValid(cached)) {
                return@withLock DailyBriefingModel(
                    briefing = cached.briefingContent,
                    oracleInsight = cached.oracleContent
                )
            }

            // Still Invalid/Missing -> Proceed to Generate
            if (cached != null) {
                Log.d("DailyBriefing", "Briefing cache invalidated for $dateStr — content invalid.")
            }

            // Fetch Context (Weather)
            val weather = try {
                if (date == LocalDate.now()) {
                    weatherRepository.getCurrentWeatherAndLocation()
                } else {
                    null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d("DailyBriefing", "Briefing context/generation failed", e)
                null
            }

            // Generate Content safely
            val newBriefing = try {
                briefingGenerator.generateBriefing(weather)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d("DailyBriefing", "Briefing context/generation failed", e)
                null
            }

            val newOracle = try {
                if (date == LocalDate.now()) {
                    oracleGenerator.generateDailyInsight()
                } else {
                    null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d("DailyBriefing", "Briefing context/generation failed", e)
                null
            }

            // 3. Safe Save Logic (Write-Through Cache)
            // Validate NEW content before saving.
            val isBriefingValid = !newBriefing.isNullOrBlank() && 
                                  newBriefing.length >= 20 && 
                                  !newBriefing.contains("Chargement", ignoreCase = true) && 
                                  !newBriefing.contains("Météo du jour", ignoreCase = true) &&
                                  !newBriefing.startsWith("...")

            val isOracleValid = newOracle == null || (
                                newOracle.length >= 10 && 
                                !newOracle.contains("Carpe Diem", ignoreCase = true))

            if (newBriefing != null && isBriefingValid && isOracleValid) {
                val entity = DailyBriefingEntity(
                    date = dateStr,
                    briefingContent = newBriefing,
                    oracleContent = newOracle
                )
                dao.insertBriefing(entity)
                Log.d("DailyBriefing", "Saved valid briefing to DB for $dateStr")
                return@withLock DailyBriefingModel(newBriefing, newOracle)
            } else {
                Log.d("DailyBriefing", "Generated content invalid — aborting save to protect DB.")
            }

            // 4. Fallback (Generation Failed or Returned Garbage)
            // Return existing (even if invalid) content to prevent blank screen, but DO NOT overwrite DB.
            if (cached != null) {
                return@withLock DailyBriefingModel(cached.briefingContent, cached.oracleContent)
            }

            // Ultimate Fallback
            DailyBriefingModel(
                briefing = "Briefing temporarily unavailable. Please check your network.",
                oracleInsight = null
            )
        }
    }

    private fun isValid(entity: DailyBriefingEntity?): Boolean {
        if (entity == null) return false
        val content = entity.briefingContent
        
        if (content.isBlank()) return false
        if (content.length < 20) return false
        
        // Strict Placeholder Checks
        if (content.contains("Chargement", ignoreCase = true)) return false
        if (content.contains("Météo du jour", ignoreCase = true)) return false
        if (content.contains("Carpe Diem", ignoreCase = true)) return false
        if (content.startsWith("...")) return false
        if (content.equals("Loading...", ignoreCase = true)) return false

        // Oracle Checks (if present)
        val oracle = entity.oracleContent
        if (oracle != null) {
            if (oracle.isBlank()) return false 
            if (oracle.length < 10) return false
            if (oracle == "..." || oracle == "Chargement") return false
            if (oracle.contains("Carpe Diem", ignoreCase = true)) return false
        }

        return true
    }
}
