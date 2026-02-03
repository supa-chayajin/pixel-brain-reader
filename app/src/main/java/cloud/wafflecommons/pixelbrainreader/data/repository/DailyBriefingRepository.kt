package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.ai.OracleGenerator
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyBriefingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBriefingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun getBriefingForDate(date: LocalDate): DailyBriefingModel = withContext(Dispatchers.IO) {
        val dateStr = date.toString()
        
        // 1. Valid Cache?
        val cached = dao.getBriefing(dateStr)
        if (cached != null) {
            return@withContext DailyBriefingModel(
                briefing = cached.briefingContent,
                oracleInsight = cached.oracleContent
            )
        }
        
        // 2. Cache Miss - Generate
        // Fetch Context (Weather)
        // If today, get current. If past, maybe null or historical (Repo handles logic or we pass null)
        val weather = if (date == LocalDate.now()) {
            weatherRepository.getCurrentWeatherAndLocation()
        } else {
             // For simplicity, or use historical if available. 
             // BriefingGenerator handles null gracefully ("Weather data unavailable").
             null 
        }

        // Generate Content (Parallelize could be better but sequential is safer for now)
        val briefing = briefingGenerator.generateBriefing(weather)
        
        // Only generate Oracle for Today (or if forced). 
        // Logic: The generators often rely on "current state". 
        // If checking past date, generators might pull "current" health data unless they support date args.
        // OracleGenerator.generateDailyInsight() uses "Instant.now()". So it's always "Today's Insight".
        // Use it only if date is Today.
        val oracle = if (date == LocalDate.now()) {
            oracleGenerator.generateDailyInsight()
        } else {
            null
        }

        // 3. Cache It
        val entity = DailyBriefingEntity(
            date = dateStr,
            briefingContent = briefing,
            oracleContent = oracle
        )
        dao.insertBriefing(entity)
        
        DailyBriefingModel(briefing, oracle)
    }
}
