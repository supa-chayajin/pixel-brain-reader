package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateRepository @Inject constructor(
    private val fileDao: FileDao,
    private val fileRepository: FileRepository,
    private val fileContentDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
) {
    companion object {
        const val TEMPLATE_FOLDER = "99_System/Templates"
    }

    /**
     * Get list of Markdown template files.
     * Returns list of filenames (e.g. "T_Daily.md").
     */
    suspend fun getAvailableTemplates(): List<String> {
        // Ensure folder exists (Logical check, repo might handle creating it if missing when accessing)
        // We query the DB for children of this path
        
        // Strategy: Use FileDao directly for speed/offline access
        val templates = fileDao.getFiles(TEMPLATE_FOLDER).firstOrNull() ?: emptyList()
        
        return templates
            .filter { it.type == "file" && it.name.endsWith(".md", ignoreCase = true) }
            .map { it.name }
            .sorted()
    }

    suspend fun getTemplateContent(path: String): String? {
        // Check if path is within templates folder for safety?
        // For now, trust the caller (VM) passes a valid path
        return try {
            fileContentDao.getContent(path)
        } catch (e: Exception) {
            null
        }
    }
}
