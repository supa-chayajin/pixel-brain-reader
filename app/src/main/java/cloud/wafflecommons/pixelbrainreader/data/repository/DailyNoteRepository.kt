package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyNoteRepository @Inject constructor(
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
        
        // Target Path: 10_Journal/YYYY-MM-DD.md
        // Note: User asked for 10_Journal/Daily/YYYY/MM structure in optimization step,
        // but their basic request "Feature C" says "10_Journal/YYYY-MM-DD.md".
        // HOWEVER, the Templater script handles the move.
        // So we should create it at the ROOT of 10_Journal (or Daily Inbox) and let Templater/User handle it?
        // Let's stick to the Spec: 10_Journal/YYYY-MM-DD.md
        // Wait, Spec says: "Check: Does 10_Journal/YYYY-MM-DD.md exist?"
        val fileName = "$year-$month-$day.md"
        val targetPath = "$JOURNAL_ROOT/$fileName"

        // 1. Check if exists (Fast check via DAO)
        val existing = fileDao.getFile(targetPath)
        if (existing != null) {
            Log.d("DailyNote", "Found existing daily note: $targetPath")
            return@withContext targetPath
        }

        // 2. Create if missing
        Log.d("DailyNote", "Creating new daily note: $targetPath")
        
        // A. Ensure Folder Exists
        fileRepository.createLocalFolder(JOURNAL_ROOT)

        // B. Get Template Content
        var templateContent = fileContentDao.getContent(TEMPLATE_PATH)
        if (templateContent.isNullOrBlank()) {
            Log.w("DailyNote", "Template not found at $TEMPLATE_PATH. Using default.")
            templateContent = DEFAULT_TEMPLATE
        }

        // C. Replace Placeholders (Basic "Mustache" engine)
        // {{date}} -> YYYY-MM-DD
        // {{time}} -> HH:mm
        // {{title}} -> YYYY-MM-DD
        val noteTitle = "$year-$month-$day"
        
        val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(templateContent, noteTitle)

        // D. Save File
        fileRepository.saveFileLocally(targetPath, processedContent)

        return@withContext targetPath
    }
}
