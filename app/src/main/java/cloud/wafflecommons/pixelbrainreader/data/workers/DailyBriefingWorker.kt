package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
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
    private val noteRepository: NoteRepository,
    private val fileRepository: FileRepository, // Keep for fallback/legacy or remove if unused? Repo replaces it.
    private val weatherRepository: WeatherRepository,
    private val moodRepository: MoodRepository,
    private val newsRepository: NewsRepository,
    private val briefingGenerator: BriefingGenerator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        val formattedDate = today.format(DateTimeFormatter.ISO_DATE)
        val notePath = "10_Journal/$formattedDate.md"

        // 1. Ensure Note Exists via NoteRepository (or fallback)
        // Note: NoteRepository acts on vault root.
        var content = noteRepository.getNoteContent(notePath)
        
        if (content == null) {
            // Create if missing
            content = "# 📅 $formattedDate\n"
            noteRepository.saveNote(notePath, content)
        }

        // 2. Check if already briefed
        if (content.contains("## 🌅 Morning Briefing")) {
            return Result.success() // Already done
        }

        // 3. Fetch Data & Log
        val weather = weatherRepository.getCurrentWeatherAndLocation()
        Log.d("WeatherAI", "Weather data fetched: ${weather?.emoji} ${weather?.temperature}")

        // AI BRIEFING GENERATION
        val aiBriefing = briefingGenerator.generateBriefing(weather)
        
        val temp = weather?.temperature ?: "?°C"
        val weatherIcon = weather?.emoji ?: "🌤️"
        val location = weather?.location ?: "Unknown"
        
        val sparkline = moodRepository.getWeeklySparkline()
        
        // AI MOOD QUOTE
        val moodTrend = try {
            val yesterdayMood = moodRepository.getDailyMood(today.minusDays(1)).firstOrNull()
            if ((yesterdayMood?.summary?.averageScore ?: 0.0) > 3.0) "Positive" else "Reflective"
        } catch (e: Exception) { "Neutral" }
        
        val quote = briefingGenerator.getDailyQuote(moodTrend)
        
        // 4. Format Markdown
        val briefingMd = """
            
            ## 🌅 Morning Briefing
            * **Météo ($location) :** $weatherIcon $temp
            * **Briefing :** $aiBriefing
            * **Mood 7j :** $sparkline
            * **Mindset :** $quote
        """.trimIndent()

        // 5. Injection Strategy
        // Update Content
        val updatedContent = if (content.contains("## 📝 Journal")) {
             // Insert before Journal to match UX placement
             content.replace("## 📝 Journal", "$briefingMd\n\n## 📝 Journal")
        } else {
             // Append if structure unknown
             "$content\n$briefingMd"
        }

        noteRepository.saveNote(notePath, updatedContent)

        return Result.success()
    }
}

