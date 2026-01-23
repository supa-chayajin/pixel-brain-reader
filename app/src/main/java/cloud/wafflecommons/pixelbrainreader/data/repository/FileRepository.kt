package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow // Kept for compatibility but unused
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade for the new Repo Architecture.
 * Delegates to NoteRepository, VaultDiscoveryRepository, AssetRepository.
 * @deprecated Use specific repositories directly.
 */
@Singleton
class FileRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val jGitProvider: JGitProvider, // Still needed for Direct Sync ops
    @ApplicationContext private val context: Context
) {

    // Legacy Bus - No-op now as flow is SSOT
    private val _fileUpdates = MutableSharedFlow<String>(replay = 0)
    val fileUpdates = _fileUpdates.asSharedFlow()

    fun getFilesFlow(path: String): Flow<List<FileEntity>> = vaultDiscoveryRepository.getAllFilesFlow(path)
    
    // Compatibility alias
    fun getFiles(path: String) = getFilesFlow(path)
    
    suspend fun getAllFolders() = emptyList<String>() // TODO: Add to VaultDiscovery if needed

    fun searchFiles(query: String) = vaultDiscoveryRepository.searchFiles(query)

    suspend fun readFile(path: String): String? = noteRepository.getNoteContent(path)

    fun getFileContentFlow(path: String): Flow<String?> = flow {
         emit(readFile(path))
         // No more bus tracking, UI should reload on DB trigger if we had a full reactive stack
         // For now, this is a simple one-shot or manual reload.
    }
    
    suspend fun saveFileLocally(path: String, content: String) {
        noteRepository.saveNote(path, content)
        _fileUpdates.emit(path)
    }

    suspend fun saveAndSync(path: String, content: String, owner: String? = null, repo: String? = null): Result<Unit> {
        noteRepository.saveNote(path, content)
        return syncRepository(owner, repo)
    }

    suspend fun syncRepository(owner: String? = null, repo: String? = null, branch: String = "main"): Result<Unit> {
        // 1. Setup/Clone
         val remoteUrl = if (owner != null && repo != null) "https://github.com/$owner/$repo.git" else null
         jGitProvider.setupRepository(remoteUrl)
         
         // 2. Commit
         jGitProvider.addAll()
         jGitProvider.commit("Auto-sync")
         
         // 3. Pull
         val pullResult = jGitProvider.pull()
         
         // 4. Push
         val pushResult = jGitProvider.push()
         
         // 5. Reindex (Legacy)
         vaultDiscoveryRepository.reindexAll()
         
              // 6. Trigger Full Indexing Worker (Immediate)
              val workRequest = androidx.work.OneTimeWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker>()
                 .setInputData(androidx.work.workDataOf("FULL_REINDEX" to true))
                 .addTag("smart_indexing")
                 .build()
              
              androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
              Log.d("FileRepository", "Scheduled Smart Indexing (Immediate)")
         
         return pushResult
    }
    
    // --- Shim Methods ---
    
    suspend fun createLocalFolder(path: String) {
        // Simple mkdir logic, better in VaultDiscovery or NoteRepo
        java.io.File(context.filesDir, "vault/$path").mkdirs()
        vaultDiscoveryRepository.reindexAll()
    }
    
    suspend fun renameFileSafe(oldPath: String, newPath: String) {
         val root = java.io.File(context.filesDir, "vault")
         val old = java.io.File(root, oldPath)
         val new = java.io.File(root, newPath)
         if(old.renameTo(new)) {
             jGitProvider.addAll()
             jGitProvider.commit("Rename $oldPath to $newPath")
             vaultDiscoveryRepository.reindexAll()
         }
    }
    
    suspend fun deleteFile(path: String, owner: String? = null, repo: String? = null): Result<Unit> {
         val root = java.io.File(context.filesDir, "vault")
         val file = java.io.File(root, path)
         if(file.delete()) {
             jGitProvider.addAll()
             jGitProvider.commit("Delete $path")
             vaultDiscoveryRepository.reindexAll()
         }
         return Result.success(Unit)
    }
    
     suspend fun resolveLink(targetPath: String): FileEntity? {
        // This logic belongs in VaultDiscovery
        // But for now, direct File check or DB check
        // We lack direct DB access here if we don't inject DAO, but VaultDiscovery has it.
        // Let's assume VaultDiscovery should have `findFile`.
        // Ideally we expose `findFile` in VaultDiscovery.
        return null // Placeholder - better to fail than crash if we removed DAO
    }
    
    suspend fun pushDirtyFiles(owner: String, repo: String, message: String? = null): Result<Unit> {
        jGitProvider.commit(message ?: "Auto-sync")
        return jGitProvider.push()
    }

    suspend fun refreshFileContent(path: String, downloadUrl: String): Result<Unit> {
        val res = jGitProvider.pull()
        vaultDiscoveryRepository.reindexAll()
        return res
    }

    suspend fun renameAndSync(oldPath: String, newPath: String, owner: String?, repo: String?): Result<Unit> {
        renameFileSafe(oldPath, newPath)
        return saveAndSync(newPath, readFile(newPath) ?: "", owner, repo)
    }
    
    // Helpers
    suspend fun fileExists(path: String) = java.io.File(context.filesDir, "vault/$path").exists()
    suspend fun createFile(path: String, initialContent: String) = saveFileLocally(path, initialContent)
    suspend fun updateFile(path: String, content: String) = saveFileLocally(path, content)
    suspend fun getFileContentFlowLegacy(path: String) = getFileContentFlow(path)
    fun getLocalFile(path: String): java.io.File {
        return java.io.File(context.filesDir, "vault/$path")
    }
}
