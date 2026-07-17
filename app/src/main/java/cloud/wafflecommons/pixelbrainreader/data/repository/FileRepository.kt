package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow // Kept for compatibility but unused
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Live label of the current repo-sync sub-step (null when idle) so the Repo/File-detail
    // pull-to-refresh indicator can show WHAT is being refreshed, not a generic string.
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun getFilesFlow(path: String): Flow<List<FileEntity>> = vaultDiscoveryRepository.getAllFilesFlow(path)
    
    // Compatibility alias
    fun getFiles(path: String) = getFilesFlow(path)
    
    suspend fun getAllFolders(): List<String> = vaultDiscoveryRepository.getAllFolderPaths()

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
      try {
        // 1. Setup/Clone
         _syncStatus.value = "Connexion au dépôt…"
         val remoteUrl = if (owner != null && repo != null) "https://github.com/$owner/$repo.git" else null
         jGitProvider.setupRepository(remoteUrl).onFailure { error ->
             Log.e("FileRepository", "Setup repository failed during sync", error)
             return Result.failure(error)
         }

         // 2. Commit
         _syncStatus.value = "Validation locale…"
         jGitProvider.addAll()
         jGitProvider.commit("Auto-sync")

         // 3. Pull
         _syncStatus.value = SyncStep.PULLING.label
         val pullResult = jGitProvider.pull()
         if (pullResult is cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult.ResolvedWithConflicts) {
             Log.w("FileRepository", "Sync completed, but ${pullResult.backedUpFilesCount} conflicts were defensively backed up.")
             // Eventual UI notification hook could go here
         } else if (pullResult is cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult.Error) {
             Log.e("FileRepository", "Pull failed during sync", pullResult.exception)
             return Result.failure(pullResult.exception)
         }

         // 4. Push
         _syncStatus.value = SyncStep.PUSHING.label
         val pushResult = jGitProvider.push()

         // 5. Reindex file table (so the UI sees post-pull state).
         // Embedding indexing is now exclusively triggered by the user from
         // Settings → "Index Knowledge Vault" (manual). We do NOT enqueue
         // IndexingWorker here.
         _syncStatus.value = SyncStep.INDEXING.label
         vaultDiscoveryRepository.reindexAll()

         return pushResult
      } finally {
         _syncStatus.value = null
      }
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
         
         jGitProvider.removeFile(oldPath)
         if(old.renameTo(new)) {
             // Reindex updates Room Database
             vaultDiscoveryRepository.reindexAll()
             jGitProvider.addFile(newPath)
             jGitProvider.commit("Rename $oldPath to $newPath")
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
    
     suspend fun resolveLink(targetPath: String): FileEntity? =
        vaultDiscoveryRepository.resolveLink(targetPath)
    
    suspend fun pushDirtyFiles(owner: String, repo: String, message: String? = null): Result<Unit> {
        jGitProvider.commit(message ?: "Auto-sync")
        return jGitProvider.push()
    }

    suspend fun refreshFileContent(path: String, downloadUrl: String): Result<Unit> {
        val res = jGitProvider.pull()
        vaultDiscoveryRepository.reindexAll()
        return if (res is cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult.Error) {
            Result.failure(res.exception)
        } else {
            Result.success(Unit)
        }
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
