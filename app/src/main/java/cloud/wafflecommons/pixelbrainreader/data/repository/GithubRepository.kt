package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.remote.GithubApiService
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.remote.GitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.model.RemoteFile
import cloud.wafflecommons.pixelbrainreader.data.remote.GitTreeResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GithubRepository @Inject constructor(
    private val apiService: GithubApiService
) : GitProvider {
    override suspend fun getContents(owner: String, repo: String, path: String): Result<List<RemoteFile>> {
        return try {
            val response = apiService.getContents(owner, repo, path)
            if (response.isSuccessful) {
                val dtos = response.body() ?: emptyList()
                val remoteFiles = dtos.map { dto ->
                    RemoteFile(
                        name = dto.name,
                        path = dto.path,
                        sha = dto.sha ?: "",
                        size = 0, // DTO doesn't have size in the snippet we saw, default to 0
                        url = dto.downloadUrl ?: "",
                        htmlUrl = "", // Missing in DTO
                        gitUrl = "", // Missing in DTO
                        downloadUrl = dto.downloadUrl,
                        type = dto.type,
                        links = null
                    )
                }
                Result.success(remoteFiles)
            } else {
                Result.failure(Exception("API Error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFileContent(url: String): Result<String> {
        return try {
            val response = apiService.getFileContent(url)
            Result.success(response.string())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFileSha(owner: String, repo: String, path: String): Result<String?> {
        return try {
            val response = apiService.getFileMetadata(owner, repo, path)
            if (response.isSuccessful) {
                Result.success(response.body()?.sha)
            } else if (response.code() == 404) {
                Result.success(null) // New File
            } else {
                Result.failure(Exception("Failed to get SHA: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pushFile(owner: String, repo: String, path: String, content: String, sha: String?, message: String): Result<Unit> {
        return try {
            val contentBase64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
            val body = mutableMapOf(
                "message" to message,
                "content" to contentBase64
            )
            if (sha != null) {
                body["sha"] = sha
            }

            val response = apiService.putContents(owner, repo, path, body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Push Failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(owner: String, repo: String, path: String, sha: String, message: String): Result<Unit> {
        return try {
            val body = mapOf(
                "message" to message,
                "sha" to sha
            )
            val response = apiService.deleteFile(owner, repo, path, body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Delete Failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
             Result.failure(e)
        }
    }

    override suspend fun getGitTree(owner: String, repo: String, sha: String): Result<GitTreeResponse> {
        return try {
            val response = apiService.getGitTree(owner, repo, sha)
            if (response.isSuccessful) {
                val body = response.body() ?: return Result.failure(Exception("Empty Tree Body"))
                Result.success(body)
            } else {
                Result.failure(Exception("Tree Fetch Failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Throwable) {
            Result.failure<GitTreeResponse>(e)
        }
    }
}
