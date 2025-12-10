package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.remote.model.RemoteFile
import cloud.wafflecommons.pixelbrainreader.data.remote.GitTreeResponse
import javax.inject.Inject

class GitlabProvider @Inject constructor(
    // private val apiService: GitlabApiService // TODO: Implement GitlabApiService
) : GitProvider {
    override suspend fun getContents(owner: String, repo: String, path: String): Result<List<RemoteFile>> {
        // Placeholder for GitLab API implementation
        return Result.failure(NotImplementedError("GitLab integration coming soon"))
    }

    override suspend fun getFileContent(url: String): Result<String> {
        // Placeholder for GitLab API implementation
        return Result.failure(NotImplementedError("GitLab integration coming soon"))
    }

    override suspend fun getFileSha(owner: String, repo: String, path: String): Result<String?> {
        return Result.failure(NotImplementedError("GitLab integration coming soon"))
    }

    override suspend fun pushFile(owner: String, repo: String, path: String, content: String, sha: String?, message: String): Result<Unit> {
        return Result.failure(NotImplementedError("GitLab Push not implemented yet"))
    }

    override suspend fun deleteFile(owner: String, repo: String, path: String, sha: String, message: String): Result<Unit> {
        return Result.failure(NotImplementedError("GitLab Delete not implemented yet"))
    }
    
    override suspend fun getGitTree(owner: String, repo: String, sha: String): Result<GitTreeResponse> {
        return Result.failure(NotImplementedError("GitLab Tree not implemented yet"))
    }
}
