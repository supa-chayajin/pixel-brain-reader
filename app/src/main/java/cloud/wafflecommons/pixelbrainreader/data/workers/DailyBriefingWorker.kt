package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
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
        // For 'Morning Briefing', usually the file is created by check-in. 
        // But prompt says "Check/Create Today's Note".
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
        val advice = if (weather != null) weatherRepository.getParentingAdvice(weather) else "Profiter de la journée"
        val temp = weather?.temperature ?: "?°C"
        val weatherIcon = weather?.emoji ?: "🌤️"
        
        val sparkline = moodRepository.getWeeklySparkline()
        val quote = briefingGenerator.getDailyQuote()
        val news = newsRepository.getTopHeadlines()

        // 4. Format Markdown
        val newsMd = news.joinToString("\n    * ") { "[${it.title}](${it.url})" }
        val briefingMd = """
            
            ## 🌅 Morning Briefing
            * **Météo :** $weatherIcon $temp - *$advice*
            * **Mood 7j :** $sparkline
            * **Mindset :** $quote
            * **Veille :**
                * $newsMd
        """.trimIndent()

        // 5. Injection Strategy
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
