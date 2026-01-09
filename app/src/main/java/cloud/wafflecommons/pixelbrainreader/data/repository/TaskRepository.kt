package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.first

@Singleton
class TaskRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
) {
    private val JOURNAL_ROOT = "10_Journal"
    private val editMutex = Mutex()

    // Regex: Start of line, optional whitespace, dash, space, bracket, x or space, bracket, space, everything else
    private val taskRegex = Regex("^(\\s*)-\\s\\[([xX ])\\]\\s*(.*)")
    private val timeRegex = Regex("(\\d{1,2}:\\d{2})|(\\d{1,2}(?:\\s*)?(?:am|pm|AM|PM))", RegexOption.IGNORE_CASE)

    suspend fun getScopedTasks(date: LocalDate): List<Task> = withContext(Dispatchers.IO) {
        val fileName = date.format(DateTimeFormatter.ISO_DATE) + ".md"
        val path = "$JOURNAL_ROOT/$fileName"
        
        val content = fileRepository.getFileContentFlow(path).first() ?: return@withContext emptyList()
        val lines = content.lines()
        
        val startHeader = "## \uD83D\uDCDD Journal"
        val endHeader = "## \uD83E\uDDE0 Idées / Second Cerveau"

        val startIndex = lines.indexOfFirst { it.trim().startsWith(startHeader) }
        val endIndex = lines.indexOfFirst { it.trim().startsWith(endHeader) }

        if (startIndex == -1) return@withContext emptyList()
        
        val effectiveEndIndex = if (endIndex == -1) lines.size else endIndex
        
        val tasks = mutableListOf<Task>()
        
        for (i in (startIndex + 1) until effectiveEndIndex) {
            val line = lines[i]
            val match = taskRegex.find(line)
            if (match != null) {
                val (indent, statusChar, text) = match.destructured
                val isCompleted = statusChar.equals("x", ignoreCase = true)
                
                // Time extraction
                var time: LocalTime? = null
                val timeMatch = timeRegex.find(text)
                if (timeMatch != null) {
                    time = parseTime(timeMatch.value)
                }

                tasks.add(Task(
                    lineIndex = i,
                    originalText = line,
                    isCompleted = isCompleted,
                    time = time,
                    cleanText = text.trim()
                ))
            }
        }
        tasks
    }

    suspend fun toggleTask(date: LocalDate, task: Task) = editMutex.withLock {
        withContext(Dispatchers.IO) {
            val fileName = date.format(DateTimeFormatter.ISO_DATE) + ".md"
            val path = "$JOURNAL_ROOT/$fileName"
            
            val content = fileRepository.getFileContentFlow(path).first() ?: return@withContext
            val lines = content.lines().toMutableList()
            
            // Safety Check: does line still match?
            if (task.lineIndex >= lines.size) return@withContext
            
            val currentLine = lines[task.lineIndex]
            val newStatusChar = if (task.isCompleted) " " else "x"
            val replacedLine = currentLine.replaceFirst(Regex("\\[([xX ])\\]"), "[$newStatusChar]")
            
            lines[task.lineIndex] = replacedLine
            
            fileRepository.saveFileLocally(path, lines.joinToString("\n"))

             // 4. Sync (Immediate Push)
            try {
                val (owner, repo) = secretManager.getRepoInfo()
                if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                    fileRepository.pushDirtyFiles(owner, repo, "feat(tasks): toggle task on $date")
                }
            } catch (e: Exception) {
                android.util.Log.e("TaskRepository", "Failed to sync", e)
            }
        }
    }

    private fun parseTime(str: String): LocalTime? {
        return try {
            val cleaned = str.trim().uppercase()
            if (cleaned.contains(":")) {
                LocalTime.parse(cleaned, DateTimeFormatter.ofPattern("H:mm"))
            } else {
                val isPm = cleaned.endsWith("PM")
                val isAm = cleaned.endsWith("AM")
                val numStr = cleaned.removeSuffix("AM").removeSuffix("PM").trim()
                var hour = numStr.toIntOrNull() ?: return null
                
                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0
                LocalTime.of(hour, 0)
            }
        } catch (e: Exception) { null }
    }
}
