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
    private val apiService: GithubApiService,
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
            val response = apiService.getContents(owner, repo, path, null)

            if (!response.isSuccessful) {
                return Result.failure(Exception("Network Error: ${response.code()} ${response.message()}"))
            }

            val body = response.body() ?: emptyList()
            val entities = body.map { it.toEntity() }

            // 2. Transactional Replace via DAO (Authoritative Sync)
            // This deletes old files in the folder before inserting new ones.
            fileDao.replaceFolderContent(path, entities)

            // Update Metadata (New ETag)
            val newEtag = response.headers()["ETag"]
            if (newEtag != null) {
                metadataDao.saveMetadata(SyncMetadataEntity(path, newEtag, System.currentTimeMillis()))
            }

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
            val response = apiService.getFileContent(downloadUrl)
            val content = response.string()
            
            // Persist
            fileContentDao.saveContent(FileContentEntity(path = path, content = content))
            Result.success(Unit)
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
                // Use getFileMetadata (Expects Single Object)
                val shaResponse = apiService.getFileMetadata(owner, repo, file.path)
                
                var sha: String? = null
                if (shaResponse.isSuccessful) {
                    val remoteDto = shaResponse.body()
                    sha = remoteDto?.sha
                    if (sha == null) {
                         throw Exception("SHA missing in successful response")
                    }
                    Log.d("PixelBrain", "SHA found: $sha")
                } else if (shaResponse.code() == 404) {
                    // New File - No SHA
                    Log.d("PixelBrain", "File not found on remote. Treating as New File.")
                    sha = null
                } else {
                    Log.e("PixelBrain", "Failed to fetch metadata for ${file.path}: ${shaResponse.code()}")
                    throw Exception("Failed to get SHA for ${file.path}: ${shaResponse.message()}")
                }

                // B. Get Local Content
                val content = fileContentDao.getContent(file.path) ?: ""
                val contentBase64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
                
                // C. PUT
                val body = mutableMapOf(
                    "message" to "Update ${file.name} via Pixel Brain",
                    "content" to contentBase64
                )
                if (sha != null) {
                    body["sha"] = sha
                }
                
                Log.d("PixelBrain", "Pushing content to ${file.path}...")
                val putResponse = apiService.putContents(owner, repo, file.path, body)
                
                if (!putResponse.isSuccessful) {
                    Log.e("PixelBrain", "Push Failed: ${putResponse.code()} ${putResponse.message()}")
                    throw Exception("Failed to push ${file.path}: ${putResponse.code()}")
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
}
