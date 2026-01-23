package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.model.NoteMetadata
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.util.Log

/**
 * Handles Markdown content operations: Read, Write, Parse Metadata.
 */
@Singleton
class NoteRepository @Inject constructor(
    private val safeFileProvider: cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider,
    private val jGitProvider: JGitProvider,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository
) {
    

    suspend fun getNoteContent(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = safeFileProvider.getSafeFile(path)
            Log.d("FileAudit", "Reading file at: ${file.absolutePath}")
            
            if (file.exists()) {
                if (file.isDirectory) return@withContext null
                val content = file.readText()
                Log.d("FileAudit", "Read ${content.length} bytes from ${file.name}")
                content
            } else {
                Log.w("FileAudit", "File not found: ${file.absolutePath}")
                null
            }
        } catch (e: SecurityException) {
            Log.e("SecurityAudit", "Access Denied: ${e.message}")
            null
        }
    }

    suspend fun getNoteMetadata(path: String): NoteMetadata = withContext(Dispatchers.IO) {
        val content = getNoteContent(path) ?: return@withContext NoteMetadata()
        FrontmatterManager.extractMetadata(content)
    }

    suspend fun saveNote(path: String, content: String) = withContext(Dispatchers.IO) {
        try {
            safeFileProvider.atomicWrite(path, content)
            
            // Trigger Git tracking
            // We defer to VaultDiscovery to update the index
            vaultDiscoveryRepository.scanSingleFile(path)
            
            // Git Add & Auto-commit if configured
            jGitProvider.addAll()
            // jGitProvider.commit("Update $path") // Optional: Auto-commit on every save?
        } catch (e: SecurityException) {
             Log.e("SecurityAudit", "Write Denied: ${e.message}")
             throw e
        }
    }
    
    suspend fun updateNoteMetadata(path: String, updates: Map<String, Any?>) = withContext(Dispatchers.IO) {
        val content = getNoteContent(path) ?: return@withContext
        val newContent = FrontmatterManager.updateFrontmatter(content, updates)
        saveNote(path, newContent)
    }
}
