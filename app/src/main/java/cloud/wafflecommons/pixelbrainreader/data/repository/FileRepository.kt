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

@Singleton
class FileRepository @Inject constructor(
    private val apiService: GithubApiService,
    private val fileDao: FileDao,
    private val metadataDao: SyncMetadataDao,
    private val fileContentDao: FileContentDao
) {
    // ... companion object

    /**
     * Get files from Local DB (Offline First).
     */
    fun getFiles(path: String): Flow<List<FileEntity>> {
        return fileDao.getFiles(path)
    }

    /**
     * Sync File List.
     * Fetches from API -> Updates DB.
     * Uses ETag for bandwidth saving.
     */
    suspend fun refreshFiles(owner: String, repo: String, path: String): Result<Unit> {
        return try {
            val metadata = metadataDao.getMetadata(path)
            val etag = metadata?.etag

            val response = apiService.getContents(owner, repo, path, etag)

            if (response.code() == 304) {
                // Not Modified. Update timestamp only?
                return Result.success(Unit)
            }

            if (!response.isSuccessful) {
                return Result.failure(Exception("Network Error: ${response.code()} ${response.message()}"))
            }

            val body = response.body() ?: emptyList()
            val entities = body.map { it.toEntity() }

            // Insert/Update
            // Note: This does not handle deletions of files that were removed from server.
            // A more robust implementation would delete files in this folder that are NOT in 'entities'.
            fileDao.insertAll(entities)

            // Save new ETag
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
}
