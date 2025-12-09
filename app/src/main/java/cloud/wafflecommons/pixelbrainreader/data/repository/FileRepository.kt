package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.toEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.GithubApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

import cloud.wafflecommons.pixelbrainreader.data.local.dao.SyncMetadataDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.SyncMetadataEntity

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import androidx.room.withTransaction

@Singleton
class FileRepository @Inject constructor(
    private val gitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.GitProvider,
    private val fileDao: FileDao,
    private val metadataDao: SyncMetadataDao,
    private val fileContentDao: FileContentDao,
    private val database: AppDatabase
) {
    // ... companion object

    /**
     * Get files from Local DB (Offline First).
     */
    fun getFilesFlow(path: String): Flow<List<FileEntity>> {
        return fileDao.getFiles(path)
    }

    // Keep getFiles for compatibility
    fun getFiles(path: String): Flow<List<FileEntity>> = getFilesFlow(path)

    /**
     * Sync File List.
     * Fetches from API -> Updates DB.
     * CRITICAL: Uses replaceFolderContent to purge ghosts (Authoritative Sync).
     */
    suspend fun refreshFolder(owner: String, repo: String, path: String): Result<Unit> {
        return try {
            // 1. Fetch Remote Content (Fresh - No ETag)
            // We consciously avoid ETag here to ensure we get the full list for the authoritative reset.
            val response = gitProvider.getContents(owner, repo, path)

            if (response.isFailure) {
                return Result.failure(response.exceptionOrNull() ?: Exception("Unknown Network Error"))
            }

            val remoteFiles = response.getOrNull() ?: emptyList()
            val entities = remoteFiles.map { it.toEntity() }

            // 2. Transactional Replace via DAO (Authoritative Sync)
            // This deletes old files in the folder before inserting new ones.
            fileDao.replaceFolderContent(path, entities)

            // Update Metadata (New ETag) - GitProvider might not return ETag exposed headers easily?
            // TODO: Handle ETag or cache control via GitProvider if needed.
            // For now, ignoring ETag save as GitProvider abstraction handles fetching.
            // If we strictly need ETag, we need to return it from getContents.
            // Result.success(Unit)

            // Mocking success for now as we don't have ETag from Generic Result
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Local-First Content Read.
     */
    fun getFileContentFlow(path: String): Flow<String?> {
        return fileContentDao.getContentFlow(path)
    }

    /**
     * Sync Content.
     * Fetches from API -> Updates DB (FileContent).
     */
    suspend fun refreshFileContent(path: String, downloadUrl: String): Result<Unit> {
        return try {
            val result = gitProvider.getFileContent(downloadUrl)
            if (result.isSuccess) {
                val content = result.getOrNull() ?: ""
                // Persist
                fileContentDao.saveContent(FileContentEntity(path = path, content = content))
                Result.success(Unit)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Unknown error fetching file content"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save File Local (Offline First).
     * Updates Content DB & Marks as Dirty.
     */
    suspend fun saveFileLocally(path: String, content: String) {
        // 1. Update Content
        fileContentDao.saveContent(FileContentEntity(path = path, content = content))
        
        // 2. Insert or Update File Entity
        val existing = fileDao.getFile(path)
        if (existing == null) {
            val newEntity = FileEntity(
                path = path,
                name = path.substringAfterLast("/"),
                type = "file",
                downloadUrl = null,
                isDirty = true,
                localModifiedTimestamp = System.currentTimeMillis()
            )
            fileDao.insertFile(newEntity)
        } else {
            fileDao.markFileAsDirty(path, true)
        }
    }

    /**
     * Push Dirty Files to Remote.
     * 1. Get Dirty Files.
     * 2. For each: Get SHA -> PUT -> Mark Clean.
     */
    suspend fun pushDirtyFiles(owner: String, repo: String): Result<Unit> {
        return try {
            val dirtyFiles = fileDao.getDirtyFiles()
            Log.d("PixelBrain", "Starting Push. Dirty files count: ${dirtyFiles.size}")
            
            for (file in dirtyFiles) {
                Log.d("PixelBrain", "Processing file: ${file.path}")

                // A. Get current remote SHA (Required for Update)
                val shaResult = gitProvider.getFileSha(owner, repo, file.path)
                
                if (shaResult.isFailure) {
                     val e = shaResult.exceptionOrNull()
                     Log.e("PixelBrain", "Failed to get SHA for ${file.path}: ${e?.message}")
                     throw e ?: Exception("Failed to get SHA")
                }

                val sha = shaResult.getOrNull()
                // If sha is null, it's a new file.

                // B. Get Local Content
                val content = fileContentDao.getContent(file.path) ?: ""
                
                // C. PUT
                val pushResult = gitProvider.pushFile(
                    owner = owner,
                    repo = repo,
                    path = file.path,
                    content = content,
                    sha = sha,
                    message = "Update ${file.name} via Pixel Brain"
                )
                
                if (pushResult.isFailure) {
                    val e = pushResult.exceptionOrNull()
                     Log.e("PixelBrain", "Push Failed for ${file.path}: ${e?.message}")
                    throw e ?: Exception("Push Failed")
                }
                
                Log.d("PixelBrain", "Push Success for ${file.path}")
                
                // D. Mark Clean
                fileDao.markFileAsDirty(file.path, false)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PixelBrain", "Push Exception", e)
            Result.failure(e)
        }
    }
    /**
     * Rename File (Local Only workaround).
     * 1. Save content to new path (Dirty).
     * 2. Delete old file.
     * Note: This does not issue a Git Move command yet.
     */
    suspend fun renameFile(oldPath: String, newPath: String) {
        val content = fileContentDao.getContent(oldPath) ?: ""
        
        // 1. Create New
        saveFileLocally(newPath, content)
        
        // 2. Delete Old
        fileDao.deleteFile(oldPath)
        // Also delete content for old path? technically yes, but it might be referenced? Use delete cascade ideally, but manual is fine.
        // For now, leaving content might be safer or just orphan it. SQLite might auto-clean if configured? No.
        // It's okay to leave orphan content for now, or add method to delete content.
    }
}
