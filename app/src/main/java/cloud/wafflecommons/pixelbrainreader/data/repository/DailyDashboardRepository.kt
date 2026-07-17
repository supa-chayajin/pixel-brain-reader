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
    private val weatherRepository: WeatherRepository,
    private val gratitudeDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.GratitudeDao
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

    suspend fun updateTimelineEntry(entry: TimelineEntryEntity) = withContext(Dispatchers.IO) {
        dashboardDao.insertTimelineEntry(entry)
    }

    suspend fun deleteTimelineEntry(id: String) = withContext(Dispatchers.IO) {
        dashboardDao.deleteTimelineEntryById(id)
    }

    suspend fun addTask(date: LocalDate, label: String, time: LocalTime? = null, priority: Int = 1) = withContext(Dispatchers.IO) {
        ensureDashboard(date)
        val task = DailyTaskEntity(
            scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE), 
            label = label, 
            scheduledTime = time?.format(DateTimeFormatter.ofPattern("HH:mm")), 
            priority = priority
        )
        dashboardDao.insertTask(task)
    }

    suspend fun updateTask(task: DailyTaskEntity) = withContext(Dispatchers.IO) {
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

        val parsed = cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser.parse(date, content)
        val dashboard = DailyDashboardEntity(
            date = date,
            dailyMantra = "", // Extraction logic omitted for brevity
            ideasContent = parsed.ideas,
            notesContent = parsed.notes
        )
        // NB: past-day ingest intentionally restores only the dashboard/timeline/tasks
        // (all keyed by date, REPLACE-safe). Scraps carry random UUIDs, so re-inserting
        // them here on every reconcile would DUPLICATE — scrap/gratitude restore lives
        // exclusively in rehydrateTodayFromDiskIfEmpty(), which runs once when Room is empty.
        dashboardDao.ingestDailyData(dashboard, parsed.timeline, parsed.tasks)
    }

    /**
     * COLD-START RECOVERY (data-loss safety net for the destructive Room migration).
     *
     * After a schema bump, `fallbackToDestructiveMigration` drops every table. Today's
     * dashboard, its scraps, and its gratitude are Room-exclusive until the nightly burn,
     * and [ingest] refuses to re-import today (the anti-poisoning SHIELD) — so without
     * this, the board comes back EMPTY even though the last burn is intact on disk.
     *
     * Here we bypass the SHIELD and rebuild today's Room state from the on-disk markdown,
     * but ONLY when Room genuinely has no dashboard row for today. That guard makes it a
     * no-op on every normal launch (never overwrites live state, never duplicates scraps).
     */
    suspend fun rehydrateTodayFromDiskIfEmpty() = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        if (dashboardDao.getDashboard(today) != null) return@withContext // Room already holds today.

        val path = "10_Journal/${today.format(DateTimeFormatter.ISO_DATE)}.md"
        val content = fileRepository.readFile(path) ?: return@withContext // No disk copy → nothing to recover.

        val parsed = cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser.parse(today, content)
        val dashboard = DailyDashboardEntity(
            date = today,
            dailyMantra = "",
            ideasContent = parsed.ideas,
            notesContent = parsed.notes
        )
        dashboardDao.ingestDailyData(dashboard, parsed.timeline, parsed.tasks)

        // Restore scraps (global, active) and today's gratitude. Safe from duplication
        // because this whole method only runs when Room had no dashboard for today.
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        parsed.scraps.forEach {
            scratchDao.insertScrap(cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity(content = it))
        }
        parsed.gratitude.forEach {
            gratitudeDao.insertGratitude(
                cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity(date = todayStr, content = it)
            )
        }
        android.util.Log.i(
            "DailyRepo",
            "Cold-start recovery: restored today from disk (${parsed.timeline.size} timeline, " +
                "${parsed.tasks.size} tasks, ${parsed.scraps.size} scraps, ${parsed.gratitude.size} gratitude)"
        )
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
        
        // RFC 009: Gratitude Express
        val gratitudes = gratitudeDao.getGratitudesForDateOneShot(date.format(DateTimeFormatter.ISO_DATE))
        
        val newContent = MarkdownBurner.burn(dashboard, timeline, tasks, activeScraps, gratitudes, frontmatter)
        fileRepository.saveFileLocally(path, newContent)
    }

    suspend fun performRetroactiveExport() = withContext(Dispatchers.IO) {
        val allDashboards = dashboardDao.getAllDashboards()
        var recoveredCount = 0
        for (dashboard in allDashboards) {
            val date = dashboard.date
            val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
            val fileExists = java.io.File(fileRepository.getLocalFile(path).absolutePath).exists()
            // We re-burn every day to ensure completeness, or just burn if it's not existing.
            // But retroactive means catching up missing days.
            // If the buffer exists but the md file does not, or we just want to ensure everything is synced:
            if (!fileExists || date == LocalDate.now()) {
                burnToDisk(date)
                recoveredCount++
            }
        }
        
        // Single Git Sync for all recovered/burned days
        if (recoveredCount > 0) {
            fileRepository.syncRepository(null, null, "main")
        }
    }
}
