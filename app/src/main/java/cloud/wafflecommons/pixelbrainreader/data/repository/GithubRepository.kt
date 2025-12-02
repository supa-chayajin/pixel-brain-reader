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
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
