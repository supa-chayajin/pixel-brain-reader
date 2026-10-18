package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade for the new Repo Architecture.
 * Delegates to NoteRepository and VaultDiscoveryRepository.
 * @deprecated Use specific repositories directly.
 */
@Singleton
class FileRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val jGitProvider: JGitProvider, // Still needed for Direct Sync ops
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager,
    @param:ApplicationContext private val context: Context
) {

    private val vaultRoot: File
        get() = File(context.filesDir, "vault")

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

    /** One-shot read wrapped in a Flow; re-collect it (e.g. keyed on a refresh counter) to re-read. */
    fun getFileContentFlow(path: String): Flow<String?> = flow {
        emit(readFile(path))
    }

    suspend fun saveFileLocally(path: String, content: String) {
        noteRepository.saveNote(path, content)
    }

    suspend fun syncRepository(owner: String? = null, repo: String? = null, branch: String = "main"): Result<Unit> {
      // Serialize this whole commit→pull→push sequence against the SyncOrchestrator's
      // foreground cycle (shared GitSyncCoordinator lock). Without it the two can
      // interleave in the gap between individual git ops and one side pushes a stale
      // ref → dropped sync. withLock (not tryLock) so an explicit user save waits for
      // an in-flight cycle rather than being silently skipped.
      return gitSyncCoordinator.mutex.withLock {
        try {
          // 1. Setup/Clone
           _syncStatus.value = "Connecting to repository…"
           // The injected singleton: constructing a SecretManager here opened a second
           // EncryptedSharedPreferences (and its Keystore key) on every sync.
           val provider = runCatching { secretManager.getProvider() }.getOrDefault("github")
           val host = if (provider.equals("gitlab", ignoreCase = true)) "gitlab.com" else "github.com"
           val remoteUrl = if (owner != null && repo != null) "https://$host/$owner/$repo.git" else null
           jGitProvider.setupRepository(remoteUrl, branch).onFailure { error ->
               Log.e("FileRepository", "Setup repository failed during sync", error)
               return@withLock Result.failure(error)
           }

           // 2. Commit (commit() stages everything itself). A commit held by the confidentiality
           // guard stops here: the edits stay staged, so the rebase couldn't run anyway.
           _syncStatus.value = "Committing locally…"
           val held = jGitProvider.commit("Auto-sync").exceptionOrNull() as? CommitHeldException
           if (held != null) return@withLock Result.failure(held)

           // 3. Pull
           _syncStatus.value = SyncStep.PULLING.label
           val pullResult = jGitProvider.pull()
           if (pullResult is SyncResult.ResolvedWithConflicts) {
               // The rebase was aborted and clean local copies backed up, so local now
               // DIVERGES from remote — a push cannot fast-forward. Same terminal
               // contract as SyncOrchestrator: stop here, never push into diverged
               // history, and surface an actionable failure instead of relying on the
               // push rejection downstream.
               Log.w("FileRepository", "Sync stopped: ${pullResult.backedUpFilesCount} conflict(s), local copies backed up.")
               return@withLock Result.failure(
                   Exception("Sync conflict: your local changes were saved. Manual merge required.")
               )
           } else if (pullResult is SyncResult.Error) {
               Log.e("FileRepository", "Pull failed during sync", pullResult.exception)
               return@withLock Result.failure(pullResult.exception)
           }

           // 4. Push
           _syncStatus.value = SyncStep.PUSHING.label
           val pushResult = jGitProvider.push()

           // 5. Reindex file table (so the UI sees post-pull state).
           _syncStatus.value = SyncStep.INDEXING.label
           vaultDiscoveryRepository.reindexAll()

           pushResult
        } finally {
           _syncStatus.value = null
        }
      }
    }

    // --- Shim Methods ---

    suspend fun createLocalFolder(path: String) = withContext(Dispatchers.IO) {
        val dir = File(vaultRoot, path)
        // Nothing to do — and, crucially, no full-vault reindex — when the folder already exists.
        // This used to reindex the entire vault on every XP grant and chore/habit config export.
        if (dir.isDirectory) return@withContext
        if (dir.mkdirs()) vaultDiscoveryRepository.reindexAll()
    }

    /**
     * Renames/moves a file or folder inside the vault and records it in a local commit (the commit
     * stages both the new path and the removal of the old one). Refuses to overwrite an existing
     * target and reports failures instead of throwing.
     */
    suspend fun renameFileSafe(oldPath: String, newPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val old = File(vaultRoot, oldPath)
        val new = File(vaultRoot, newPath)
        if (!old.exists()) return@withContext Result.failure(IOException("Source not found: $oldPath"))
        if (new.exists()) return@withContext Result.failure(IOException("An item named ${new.name} already exists there"))
        new.parentFile?.mkdirs()
        if (!old.renameTo(new)) return@withContext Result.failure(IOException("Could not rename $oldPath"))
        vaultDiscoveryRepository.reindexAll()
        jGitProvider.commit("Rename $oldPath to $newPath")
        Result.success(Unit)
    }

    /** Deletes a file or a whole folder and records the deletion in a local commit. */
    suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val file = File(vaultRoot, path)
        if (!file.exists()) return@withContext Result.failure(IOException("File not found: $path"))
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) return@withContext Result.failure(IOException("Could not delete $path"))
        vaultDiscoveryRepository.reindexAll()
        jGitProvider.commit("Delete $path")
        Result.success(Unit)
    }

    suspend fun resolveLink(targetPath: String): FileEntity? =
        vaultDiscoveryRepository.resolveLink(targetPath)

    /**
     * Commit + push, WITHOUT pulling (callers that must not bring remote changes in). A commit held
     * by the confidentiality guard doesn't stop the push: the commits already recorded passed it.
     */
    suspend fun pushDirtyFiles(owner: String, repo: String, message: String? = null): Result<Unit> {
        jGitProvider.commit(message ?: "Auto-sync")
        return jGitProvider.push()
    }

    // Helpers
    suspend fun fileExists(path: String) = withContext(Dispatchers.IO) { File(vaultRoot, path).exists() }
    suspend fun createFile(path: String, initialContent: String) = saveFileLocally(path, initialContent)
    fun getLocalFile(path: String): File {
        return File(vaultRoot, path)
    }

    /**
     * Moves an unreadable vault file aside (`<name>.corrupt-<epochMillis>`, next to the original) so
     * that rewriting the path can never silently destroy what it held. Returns the backup, or null
     * when the file is missing or could not be moved. Blocking — call from Dispatchers.IO.
     */
    fun moveAsideCorrupt(path: String): File? {
        val source = getLocalFile(path)
        if (!source.exists()) return null
        val backup = File(source.parentFile, "${source.name}.corrupt-${System.currentTimeMillis()}")
        return backup.takeIf { source.renameTo(it) }
    }

    suspend fun getFileCommitHistory(relativePath: String, limit: Int = 15): List<GitCommitDto> =
        jGitProvider.getFileCommitHistory(relativePath, limit)

    suspend fun getFileContentAtCommit(relativePath: String, commitId: String): String? =
        jGitProvider.getFileContentAtCommit(relativePath, commitId)
}
