package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

@Singleton
class DailyNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao
) {

    companion object {
        private const val JOURNAL_ROOT = "10_Journal"
        private const val TEMPLATE_PATH = "99_System/Templates/T_Daily_Journal.md"
        private const val DEFAULT_TEMPLATE = """---
title: "Journal: {{date}}"
created: {{date}}
updated: {{date}}
type: journal
status: done
tags: [journal]
---

# Journal {{date}}

## Tasks
* [ ] 

## Notes
"""
    }

    /**
     * Finds or Creates specific Daily Note for Today.
     * Returns the FILE PATH (String) of the note to open.
     */
    suspend fun getOrCreateTodayNote(): String = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val year = now.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = now.format(DateTimeFormatter.ofPattern("MM"))
        val day = now.format(DateTimeFormatter.ofPattern("dd"))
        
        val fileName = "$year-$month-$day.md"
        // THE FIX: Strict path construction, forcing 10_Journal prefix
        val targetPath = "$JOURNAL_ROOT/$fileName"

        // Bonus: Cleanup misplaced notes before proceeding
        cleanupMisplacedDailyNotes()

        // 1. Check if exists (Fast check via DAO)
        val existing = fileDao.getFile(targetPath)
        if (existing != null) {
            Log.d("DailyNote", "Found existing daily note: $targetPath")
            return@withContext targetPath
        }

        // 2. Create if missing
        Log.d("DailyNote", "Creating new daily note: $targetPath")
        
        // A. Directory Guarantee (Local FS)
        val journalDir = File(context.filesDir, JOURNAL_ROOT)
        if (!journalDir.exists()) {
            Log.d("DailyNote", "Creating directory: ${journalDir.absolutePath}")
            journalDir.mkdirs()
        }
        
        // Ensure folder entity exists in DB
        fileRepository.createLocalFolder(JOURNAL_ROOT)

        // B. Get Template Content
        var templateContent = fileContentDao.getContent(TEMPLATE_PATH)
        if (templateContent.isNullOrBlank()) {
            Log.w("DailyNote", "Template not found at $TEMPLATE_PATH. Using default.")
            templateContent = DEFAULT_TEMPLATE
        }

        // C. Replace Placeholders (Basic "Mustache" engine)
        val noteTitle = "$year-$month-$day"
        val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(templateContent, noteTitle)

        // D. Save File (Enforce safe path)
        fileRepository.saveFileLocally(targetPath, processedContent)

        return@withContext targetPath
    }

    /**
     * Cleanup: Moves or Deletes misplaced daily notes at the root.
     */
    private suspend fun cleanupMisplacedDailyNotes() {
        try {
            // Find all files at the root (path doesn't contain '/')
            val rootFiles = fileDao.getFiles("").firstOrNull() ?: emptyList()
            val dailyNoteRegex = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

            for (file in rootFiles) {
                if (dailyNoteRegex.matches(file.name)) {
                    val correctedPath = "$JOURNAL_ROOT/${file.name}"
                    val existingInFolder = fileDao.getFile(correctedPath)

                    if (existingInFolder != null) {
                        Log.d("DailyNote", "Cleaning up duplicate root file: ${file.path}")
                        fileDao.deleteFile(file.path)
                    } else {
                        Log.d("DailyNote", "Moving misplaced root file to journal: ${file.path} -> $correctedPath")
                        fileRepository.renameFileSafe(file.path, correctedPath)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DailyNote", "Failed during misplaced notes cleanup", e)
        }
    }
}
