package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NewsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.firstOrNull

@HiltWorker
class DailyBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val moodRepository: MoodRepository,
    private val newsRepository: NewsRepository,
    private val briefingGenerator: BriefingGenerator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        val formattedDate = today.format(DateTimeFormatter.ISO_DATE)
        val notePath = "10_Journal/$formattedDate.md"

        // 1. Check if file exists. If not, we might want to skip or create. 
        if (!fileRepository.fileExists(notePath)) {
            fileRepository.createFile(notePath, "# 📅 $formattedDate\n")
        }

        var content = fileRepository.readFile(notePath) ?: return Result.failure()

        // 2. Check if already briefed
        if (content.contains("## 🌅 Morning Briefing")) {
            return Result.success() // Already done
        }

        // 3. Fetch Data
        val weather = weatherRepository.getCurrentWeatherAndLocation()
        // AI WEATHER ADVICE
        // AI WEATHER ADVICE
        Log.d("DailyBriefingWorker", weather?.description ?: "null")
        val insight = if (weather != null) {
            briefingGenerator.getWeatherInsight(weather)
        } else {
            "Préparez-vous pour la journée."
        }
        
        val temp = weather?.temperature ?: "?°C"
        val weatherIcon = weather?.emoji ?: "🌤️"
        
        val sparkline = moodRepository.getWeeklySparkline()
        
        // AI MOOD QUOTE
        // Determine simple trend
        val moodTrend = try {
            val yesterdayMood = moodRepository.getDailyMood(today.minusDays(1)).firstOrNull()
            if ((yesterdayMood?.summary?.averageScore ?: 0.0) > 3.0) "Positive" else "Reflective"
        } catch (e: Exception) { "Neutral" }
        
        val quote = briefingGenerator.getDailyQuote(moodTrend)
        
        // 4. Update Frontmatter with Insight
        val updates = mapOf(
            "weather_insight" to insight
        )
        content = cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager.injectWeather(content, updates)

        // 5. Format Markdown (Without News)
        val briefingMd = """
            
            ## 🌅 Morning Briefing
            * **Météo :** $weatherIcon $temp - *$insight*
            * **Mood 7j :** $sparkline
            * **Mindset :** $quote
        """.trimIndent()

        // 6. Injection Strategy
        val insertionPoint = "## Daily Summary" // Or "## 📝 Journal" fallback
        
        content = if (content.contains("## 📝 Journal")) {
             // Insert before Journal to match UX placement
             content.replace("## 📝 Journal", "$briefingMd\n\n## 📝 Journal")
        } else {
             // Append if structure unknown
             "$content\n$briefingMd"
        }

        fileRepository.updateFile(notePath, content)

        return Result.success()
    }
}

