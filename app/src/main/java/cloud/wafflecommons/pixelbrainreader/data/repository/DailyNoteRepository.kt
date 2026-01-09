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
import java.time.LocalDate
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
     * Wrapper for backward compatibility. Use [hasNote] and [createNoteFromTemplate] for granular control.
     */
    suspend fun getOrCreateTodayNote(): String {
        val today = LocalDate.now()
        val fileName = today.format(DateTimeFormatter.ISO_DATE) + ".md"
        val targetPath = "$JOURNAL_ROOT/$fileName"
        
        if (!hasNote(today)) {
             createNoteFromTemplate(today)
        }
        
        return targetPath
    }

    /**
     * Finds or Creates specific Daily Note for Today.
     * Returns the FILE PATH (String) of the note to open.
     */
    /**
     * strict check for physical file existence.
     * Prevents false negatives from DB cache.
     */
    /**
     * strict check for file existence (CHECKING DATABASE).
     * Must be used AFTER a sync operation to ensure DB is up to date.
     */
    suspend fun hasNote(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val fileName = date.format(DateTimeFormatter.ISO_DATE) + ".md"
        val path = "$JOURNAL_ROOT/$fileName"
        // Check DB source of truth (Sync updates DB, not necessarily Disk)
        fileDao.getFile(path) != null
    }

    /**
     * Creates a new Daily Note from template.
     * WARNING: Does NOT check if file exists (Caller must check).
     */
    suspend fun createNoteFromTemplate(date: LocalDate) {
        // Legacy: Used by MainViewModel via getOrCreateTodayNote wrapper. 
        // Logic is now in createDailyNote called by VM with explicit content.
        // We can keep this for backward compatibility or refactor MainViewModel entirely.
        // For now, let's leave it but it might be redundant if MainViewModel uses it.
        // MainViewModel uses it to just "make it exist". 
        // Let's implement this by delegating if possible or keep as is.
        // Keeping as is to avoid breaking MainViewModel again.
        withContext(Dispatchers.IO) {
            val fileName = date.format(DateTimeFormatter.ISO_DATE) + ".md"
            val targetPath = "$JOURNAL_ROOT/$fileName"

            // 1. Directory Guarantee
            val journalDir = File(context.filesDir, JOURNAL_ROOT)
            if (!journalDir.exists()) journalDir.mkdirs()
            fileRepository.createLocalFolder(JOURNAL_ROOT)

            // 2. Get Template Content
            var templateContent = try {
                fileContentDao.getContent(TEMPLATE_PATH)
            } catch (e: Exception) { null }

            if (templateContent.isNullOrBlank()) {
                Log.w("DailyNote", "Template not found at $TEMPLATE_PATH. Using default.")
                templateContent = DEFAULT_TEMPLATE
            }

            // 3. Apply Template
            val title = date.format(DateTimeFormatter.ISO_DATE)
            val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(templateContent, title)

            // 4. Save (Local)
            fileRepository.saveFileLocally(targetPath, processedContent)
        }
    }

    /**
     * Creates standard daily note with provided content and PUSHES immediately.
     */
    suspend fun createDailyNote(date: LocalDate, content: String, owner: String?, repo: String?) {
         withContext(Dispatchers.IO) {
             val fileName = date.format(DateTimeFormatter.ISO_DATE) + ".md"
             val targetPath = "$JOURNAL_ROOT/$fileName"
             
             // 1. Directory Guarantee
             val journalDir = File(context.filesDir, JOURNAL_ROOT)
             if (!journalDir.exists()) journalDir.mkdirs()
             fileRepository.createLocalFolder(JOURNAL_ROOT)
             
             // 2. Save Local
             fileRepository.saveFileLocally(targetPath, content)
             
             // 3. Push (if credentials avail)
             if (owner != null && repo != null) {
                 try {
                     fileRepository.pushDirtyFiles(owner, repo, "docs(journal): create ${date}")
                 } catch (e: Exception) {
                     Log.e("DailyNote", "Failed to push new daily note", e)
                 }
             }
         }
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
