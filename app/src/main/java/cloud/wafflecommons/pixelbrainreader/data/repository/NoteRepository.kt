package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.NoteMetadata
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Markdown content operations: Read, Write, Parse Metadata.
 */
@Singleton
class NoteRepository @Inject constructor(
    private val safeFileProvider: SafeFileProvider,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository
) {

    suspend fun getNoteContent(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = safeFileProvider.getSafeFile(path)
            // Absolute paths and filenames intentionally NOT logged — note
            // filenames can be sensitive (especially private vault). Log.d
            // is stripped in release by ProGuard but Log.w/e survive, so
            // keep release-visible logs path-free.
            if (file.exists()) {
                if (file.isDirectory) return@withContext null
                file.readText()
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.e("SecurityAudit", "Access denied to vault file")
            null
        }
    }

    suspend fun getNoteMetadata(path: String): NoteMetadata = withContext(Dispatchers.IO) {
        val content = getNoteContent(path) ?: return@withContext NoteMetadata()
        FrontmatterManager.extractMetadata(content)
    }

    suspend fun saveNote(path: String, content: String) = withContext(Dispatchers.IO) {
        try {
            val target = safeFileProvider.getSafeFile(path)
            val vaultRoot = safeFileProvider.getSafeFile("")
            // Folders this write is about to create (atomicWrite mkdirs the parents), so the file
            // browser learns about them without a full-vault reindex.
            val newDirectories = generateSequence(target.parentFile) { it.parentFile }
                .takeWhile { it.absolutePath != vaultRoot.absolutePath && !it.exists() }
                .toList()

            safeFileProvider.atomicWrite(path, content)

            vaultDiscoveryRepository.scanSingleFile(path)
            newDirectories.forEach { dir ->
                vaultDiscoveryRepository.scanSingleFile(dir.relativeTo(vaultRoot).path)
            }
            // No `git add` here: JGitProvider.commit() stages everything right before it commits.
            // Staging on every save cost two full working-tree walks per write (health JSON, mood,
            // habit logs, gamification profile, autosave…) for no benefit.
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

    /** Vault-relative path of [file] (for callers holding a resolved File). */
    fun relativePathOf(file: File): String = file.relativeTo(safeFileProvider.getSafeFile("")).path
}
