package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.toEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.GithubApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(
    private val apiService: GithubApiService,
    private val fileDao: FileDao
) {

    companion object {
        private const val TAG = "FileRepository"
    }

    /**
     * Single Source of Truth: The Database.
     * The UI observes this Flow. It never asks the Network directly.
     */
    fun getFiles(parentPath: String = ""): Flow<List<FileEntity>> {
        // Fix for Root Path:
        // The UI sends "" for root.
        // FileDao needs to handle this. We pass it through.
        return fileDao.getFiles(parentPath)
    }

    /**
     * Sync Engine.
     * Fetches from GitHub -> Maps to Entity -> Replaces in DB.
     * Transactional replacement ensures UI consistency.
     */
    suspend fun refreshFiles(owner: String, repo: String, path: String = ""): Result<Unit> {
        return try {
            Log.d(TAG, "Fetching files for path: '$path' from $owner/$repo")
            
            val dtos = apiService.getContents(owner, repo, path)
            Log.d(TAG, "Fetched ${dtos.size} files from API")

            val entities = dtos.map { dto ->
                // Normalization: Ensure consistency if needed. 
                // GitHub paths are clean (e.g. "README.md"), so we usually use them as is.
                dto.toEntity()
            }
            
            Log.d(TAG, "Inserting ${entities.size} files into DB for parent context: '$path'")
            
            fileDao.insertAll(entities)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for path: $path", e)
            Result.failure(e)
        }
    }

    suspend fun getFileContent(downloadUrl: String): Result<String> {
        return try {
            val response = apiService.getFileContent(downloadUrl)
            val content = response.string()
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
