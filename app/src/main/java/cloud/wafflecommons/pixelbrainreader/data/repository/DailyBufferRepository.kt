package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyBufferDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBufferEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.utils.MarkdownBurner
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyBufferRepository @Inject constructor(
    private val dailyBufferDao: DailyBufferDao,
    private val fileRepository: FileRepository
) {

    // --- Live Data Access ---
    fun getLiveTimeline(date: LocalDate): Flow<List<TimelineEntryEntity>> {
        return dailyBufferDao.getLiveTimeline(date)
    }

    fun getLiveTasks(date: LocalDate): Flow<List<DailyTaskEntity>> {
        return dailyBufferDao.getLiveTasks(date)
    }
    
    suspend fun getBuffer(date: LocalDate): DailyBufferEntity? {
        return dailyBufferDao.getBuffer(date)
    }

    suspend fun hasBuffer(date: LocalDate): Boolean {
        return dailyBufferDao.getBuffer(date) != null
    }

    // --- Interactive Operations (Atomic Room Writes) ---
    
    suspend fun addTimelineEntry(date: LocalDate, content: String, time: LocalTime) = withContext(Dispatchers.IO) {
        // Ensure buffer exists
        ensureBuffer(date)
        
        val entry = TimelineEntryEntity(
            date = date,
            time = time,
            content = content
        )
        dailyBufferDao.insertTimelineEntry(entry)
        markBufferDirty(date)
    }

    suspend fun addTask(date: LocalDate, label: String, time: LocalTime? = null, priority: Int = 1) = withContext(Dispatchers.IO) {
        ensureBuffer(date)
        
        val task = DailyTaskEntity(
            date = date,
            label = label,
            scheduledTime = time,
            priority = priority
        )
        dailyBufferDao.insertTask(task)
        markBufferDirty(date)
    }

    suspend fun toggleTask(taskId: String, isDone: Boolean, date: LocalDate) = withContext(Dispatchers.IO) {
        dailyBufferDao.updateTaskStatus(taskId, isDone)
        markBufferDirty(date)
    }

    private suspend fun ensureBuffer(date: LocalDate) {
        if (dailyBufferDao.getBuffer(date) == null) {
            dailyBufferDao.insertBuffer(DailyBufferEntity(date = date))
        }
    }
    
    private suspend fun markBufferDirty(date: LocalDate) {
        dailyBufferDao.updateMantra(date, "Sync Pending...", System.currentTimeMillis()) 
        // We could trigger auto-burn here or rely on specific triggers
    }

    // --- Persistence Logic (Ingest & Burn) ---

    /**
     * Ingests a raw Markdown file into the Room Buffer.
     * Should be called on app open or when "Reload" is clicked.
     */
    suspend fun ingest(date: LocalDate, content: String) = withContext(Dispatchers.IO) {
        val lines = content.lines()
        val timelineEvents = mutableListOf<TimelineEntryEntity>()
        val tasks = mutableListOf<DailyTaskEntity>()
        
        // Simple Parser Logic (Needs to be robust)
        // 1. Timeline
        val timelineRegex = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2})\\s+(.*)")
        var inTimeline = false
        var inJournal = false
        
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("## 🗓️ Timeline") || trimmed.startsWith("## Timeline")) {
                inTimeline = true
                inJournal = false
                return@forEach
            }
            if (trimmed.startsWith("## 📝 Journal") || trimmed.startsWith("## Journal")) {
                inJournal = true
                inTimeline = false
                return@forEach
            }
            if (trimmed.startsWith("##")) { // Other section
                inTimeline = false
                inJournal = false
            }
            
            if (inTimeline && trimmed.startsWith("-")) {
                 val match = timelineRegex.find(line)
                 if (match != null) {
                     val (timeStr, text) = match.destructured
                     try {
                         val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
                         timelineEvents.add(TimelineEntryEntity(
                             date = date,
                             time = time,
                             content = text.trim(),
                             originalMarkdown = line
                         ))
                     } catch(e: Exception) {}
                 }
            }
            
            if (inJournal && trimmed.startsWith("- [")) {
                // Task Parser: "- [x] Task Label"
                val isDone = trimmed.startsWith("- [x]")
                val rawLabel = trimmed.substringAfter("] ").trim()
                
                // Extract Priority "‼️"
                val priority = if (rawLabel.contains("‼️")) 2 else 1
                val cleanLabel = rawLabel.replace("‼️", "").trim()
                
                // Extract Time "at 14:00" (Simple heuristic)
                // If user wrote "at 14:00 Call", we try to parse.
                var scheduledTime: LocalTime? = null
                var finalLabel = cleanLabel
                
                // Regex for "at HH:mm"
                val timeMatch = Regex("at (\\d{1,2}:\\d{2})").find(cleanLabel)
                if (timeMatch != null) {
                    try {
                        scheduledTime = LocalTime.parse(timeMatch.groupValues[1], DateTimeFormatter.ofPattern("H:mm"))
                        finalLabel = cleanLabel.replace(timeMatch.value, "").trim()
                    } catch(e: Exception) {}
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

        val buffer = DailyBufferEntity(date = date, mantra = extractMantra(content))
        
        dailyBufferDao.ingestDailyData(buffer, timelineEvents, tasks)
    }
    
    private fun extractMantra(content: String): String {
        // Find *Mantra* after H1
        // Simplified: First line starting with * and ending with * after H1? 
        // Or just hardcode search.
        // Let's leave empty for now as parser robustness requires more testing.
        return "" 
    }

    /**
     * Burns the Room Buffer back to disk.
     */
    suspend fun burnToDisk(date: LocalDate) = withContext(Dispatchers.IO) {
        val buffer = dailyBufferDao.getBuffer(date) ?: return@withContext
        val timeline = dailyBufferDao.getTimelineSnapshot(date)
        val tasks = dailyBufferDao.getTasksSnapshot(date)
        
        // We need existing file to preserve frontmatter and "Other Sections"
        val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
        val currentFileContent = fileRepository.readFile(path) ?: ""
        
        // Extract Frontmatter
        val frontmatter = if (currentFileContent.isNotEmpty()) {
            FrontmatterManager.extractFrontmatterRaw(currentFileContent)
        } else ""
        
        // Generate new content
        val newContent = MarkdownBurner.burn(buffer, timeline, tasks, frontmatter)
        
        // Save
        fileRepository.saveFileLocally(path, newContent)
    }
}
