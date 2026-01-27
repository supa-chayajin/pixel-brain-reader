package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.utils.MarkdownBurner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyDashboardRepository @Inject constructor(
    private val dashboardDao: DailyDashboardDao,
    private val scratchDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.ScratchDao,
    private val fileRepository: FileRepository,
    private val briefingGenerator: BriefingGenerator,
    private val weatherRepository: WeatherRepository
) {

    // --- Live Data Access (Separated Sections) ---
    
    fun getDashboard(date: LocalDate): Flow<DailyDashboardEntity?> {
        return dashboardDao.getLiveDashboard(date)
    }
    
    // We add a Flow accessor for Dashboard to observe changes (like AI completion)
    // DAO should have Flow<DailyDashboardEntity> really. 
    // I will stick to ViewModel managing state re-fetch or manual flow if needed.
    // Wait, for AI generation completion causing UI update, valid flow is better.
    // I can modify local DAO to return Flow later if needed. For now suspend is OK as AI updates trigger re-load in VM.
    
    fun getLiveTimeline(date: LocalDate): Flow<List<TimelineEntryEntity>> {
        return dashboardDao.getLiveTimeline(date)
    }

    fun getLiveTasks(date: LocalDate): Flow<List<DailyTaskEntity>> {
        return dashboardDao.getLiveTasks(date)
    }
    
    suspend fun hasBuffer(date: LocalDate): Boolean {
        return dashboardDao.getDashboard(date) != null
    }

    // --- AI Efficiency Engine ---

    /**
     * "One Generation Per Day" Policy.
     */
    suspend fun getOrGenerateBriefing(date: LocalDate): Pair<String, String> = withContext(Dispatchers.IO) {
        val dashboard = dashboardDao.getDashboard(date)
        
        // 1. Check Cache
        if (dashboard != null && dashboard.aiWeatherBriefing != null && dashboard.aiQuoteOfTheDay != null) {
            val isToday = date == LocalDate.now()
            val hasTimestamp = dashboard.lastAiGenerationTimestamp != null
            val isFresh = if (hasTimestamp) {
                // Check if generated TODAY (Day of Year)
                val genDate = java.time.Instant.ofEpochMilli(dashboard.lastAiGenerationTimestamp!!).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                genDate == date // Generated for this date implies it's valid for this date's report.
                true 
            } else false
            
            // If cached data exists and is targeted for this date, use it.
            return@withContext Pair(dashboard.aiWeatherBriefing, dashboard.aiQuoteOfTheDay)
        }
        
        // 2. Generate
        val weather = if (date == LocalDate.now()) weatherRepository.getCurrentWeatherAndLocation() 
                      else weatherRepository.getHistoricalWeather(date)

        val weatherBriefing = if (weather != null) {
            briefingGenerator.getWeatherInsight(weather)
        } else {
            "Météo indisponible."
        }
        
        val quote = briefingGenerator.getDailyQuote("Neutral") 
        
        // 3. Update DB (Cache)
        ensureDashboard(date)
        dashboardDao.updateAiBriefing(
            date = date, 
            weather = weatherBriefing, 
            quote = quote, 
            timestamp = System.currentTimeMillis()
        )
        
        return@withContext Pair(weatherBriefing, quote)
    }

    // --- Second Brain Persistence ---
    
    suspend fun updateSecondBrain(date: LocalDate, type: String, content: String) = withContext(Dispatchers.IO) {
        ensureDashboard(date)
        if (type == "IDEAS") {
            dashboardDao.updateIdeas(date, content)
        } else {
            dashboardDao.updateNotes(date, content)
        }
    }

    // --- Core Operations ---

    suspend fun addTimelineEntry(date: LocalDate, content: String, time: LocalTime) = withContext(Dispatchers.IO) {
        ensureDashboard(date)
        val entry = TimelineEntryEntity(date = date, time = time, content = content)
        dashboardDao.insertTimelineEntry(entry)
    }

    suspend fun addTask(date: LocalDate, label: String, time: LocalTime? = null, priority: Int = 1) = withContext(Dispatchers.IO) {
        ensureDashboard(date)
        val task = DailyTaskEntity(date = date, label = label, scheduledTime = time, priority = priority)
        dashboardDao.insertTask(task)
    }

    suspend fun toggleTask(taskId: String, isDone: Boolean) = withContext(Dispatchers.IO) {
        dashboardDao.updateTaskStatus(taskId, isDone)
    }

    private suspend fun ensureDashboard(date: LocalDate) {
        if (dashboardDao.getDashboard(date) == null) {
            dashboardDao.insertDashboard(DailyDashboardEntity(date = date))
        }
    }

    // --- Ingest & Burn (The Bridge) ---

    suspend fun ingest(date: LocalDate, content: String) = withContext(Dispatchers.IO) {
        // TOTAL ISOLATION SHIELD: 
        // The dashboard is an "Iron Vault" for Today.
        // We NEVER import today's file back into Room to avoid "Data Poisoning" (Git Pull overwriting local typing).
        if (date == LocalDate.now()) {
            android.util.Log.d("DailyRepo", "SHIELD ACTIVE: Blocking file ingest for TODAY. Room is the exclusive source of truth.")
            return@withContext
        }
        
        val lines = content.lines()
        val timelineEvents = mutableListOf<TimelineEntryEntity>()
        val tasks = mutableListOf<DailyTaskEntity>()
        var ideas = StringBuilder()
        var notes = StringBuilder()
        
        var section = "HEADER" 
        
        val timelineRegex = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2})\\s+(.*)")
        
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("## 🗓️ Timeline") || trimmed.startsWith("## Timeline")) { section = "TIMELINE"; return@forEach }
            if (trimmed.startsWith("## 📝 Journal") || trimmed.startsWith("## Journal")) { section = "JOURNAL"; return@forEach }
            if (trimmed.startsWith("## 🧠 Idées") || trimmed.startsWith("## Idées")) { section = "IDEAS"; return@forEach }
            if (trimmed.startsWith("## 📑 Notes") || trimmed.startsWith("## Notes")) { section = "NOTES"; return@forEach }
            if (trimmed.startsWith("##")) return@forEach // Skip other headers
            
            when(section) {
                "TIMELINE" -> {
                     val match = timelineRegex.find(line)
                     if (match != null) {
                         val (timeStr, text) = match.destructured
                         try {
                              timelineEvents.add(TimelineEntryEntity(
                                  date = date,
                                  time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm")),
                                  content = text.trim()
                              ))
                         } catch(e: Exception){}
                     }
                }
                "JOURNAL" -> {
                    if (trimmed.startsWith("- [")) {
                        val isDone = trimmed.startsWith("- [x]")
                        val rawLabel = trimmed.substringAfter("] ").trim()
                        val priority = if (rawLabel.contains("‼️")) 2 else 1
                        val cleanLabel = rawLabel.replace("‼️", "").trim()
                        var scheduledTime: LocalTime? = null
                        var finalLabel = cleanLabel
                        
                        val timeMatch = Regex("at (\\d{1,2}:\\d{2})").find(cleanLabel)
                        if (timeMatch != null) {
                            try {
                                scheduledTime = LocalTime.parse(timeMatch.groupValues[1], DateTimeFormatter.ofPattern("H:mm"))
                                finalLabel = cleanLabel.replace(timeMatch.value, "").trim()
                            } catch(e: Exception){}
                        }
                        
                        tasks.add(DailyTaskEntity(
                            date = date, 
                            label = finalLabel, 
                            isDone = isDone, 
                            priority = priority, 
                            scheduledTime = scheduledTime
                        ))
                    }
                }
                "IDEAS" -> { if (line.isNotBlank() || ideas.isNotEmpty()) ideas.append(line).append("\n") }
                "NOTES" -> { if (line.isNotBlank() || notes.isNotEmpty()) notes.append(line).append("\n") }
            }
        }
        
        val dashboard = DailyDashboardEntity(
            date = date,
            dailyMantra = "", // Extraction logic omitted for brevity
            ideasContent = ideas.toString().trim(),
            notesContent = notes.toString().trim()
        )
        
        dashboardDao.ingestDailyData(dashboard, timelineEvents, tasks)
    }

    suspend fun burnToDisk(date: LocalDate) = withContext(Dispatchers.IO) {
        val dashboard = dashboardDao.getDashboard(date) ?: return@withContext
        val timeline = dashboardDao.getTimelineSnapshot(date)
        val tasks = dashboardDao.getTasksSnapshot(date)
        
        val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
        val currentFileContent = fileRepository.readFile(path) ?: ""
        val frontmatter = if (currentFileContent.isNotEmpty()) FrontmatterManager.extractFrontmatterRaw(currentFileContent) else ""
        
        // RFC 007: Include active (unpromoted) scraps in the burn
        val activeScraps = scratchDao.getActiveScrapsSync()
        
        val newContent = MarkdownBurner.burn(dashboard, timeline, tasks, activeScraps, frontmatter)
        fileRepository.saveFileLocally(path, newContent)
    }
}
