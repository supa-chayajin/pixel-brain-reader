package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.remote.model.RemoteFile
import cloud.wafflecommons.pixelbrainreader.data.repository.GithubRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DelegatingGitProvider @Inject constructor(
    private val secretManager: SecretManager,
    private val githubProvider: GithubRepository,
    private val gitlabProvider: GitlabProvider
) : GitProvider {

    private fun getProvider(): GitProvider {
        return when (secretManager.getProvider()) {
            "gitlab" -> gitlabProvider
            else -> githubProvider
        }
    }

    override suspend fun getContents(owner: String, repo: String, path: String): Result<List<RemoteFile>> {
        return getProvider().getContents(owner, repo, path)
    }

    override suspend fun getFileContent(url: String): Result<String> {
        return getProvider().getFileContent(url)
    }

    override suspend fun getFileSha(owner: String, repo: String, path: String): Result<String?> {
        return getProvider().getFileSha(owner, repo, path)
    }

    override suspend fun pushFile(owner: String, repo: String, path: String, content: String, sha: String?, message: String): Result<Unit> {
        return getProvider().pushFile(owner, repo, path, content, sha, message)
    }

    override suspend fun deleteFile(owner: String, repo: String, path: String, sha: String, message: String): Result<Unit> {
        return getProvider().deleteFile(owner, repo, path, sha, message)
    }

    override suspend fun getGitTree(owner: String, repo: String, sha: String): Result<GitTreeResponse> {
        return getProvider().getGitTree(owner, repo, sha)
    }
}
