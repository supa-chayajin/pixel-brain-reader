package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.remote.GithubApiService
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GithubRepository @Inject constructor(
    private val apiService: GithubApiService
) {
    suspend fun getContents(owner: String, repo: String, path: String = ""): Result<List<GithubFileDto>> {
        return try {
            val response = apiService.getContents(owner, repo, path)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("API Error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFileContent(url: String): Result<String> {
        return try {
            val response = apiService.getFileContent(url)
            Result.success(response.string())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
