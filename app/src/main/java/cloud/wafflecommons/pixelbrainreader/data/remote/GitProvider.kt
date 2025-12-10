package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.remote.model.RemoteFile
import cloud.wafflecommons.pixelbrainreader.data.remote.GitTreeResponse

interface GitProvider {
    suspend fun getContents(owner: String, repo: String, path: String = ""): Result<List<RemoteFile>>
    suspend fun getFileContent(url: String): Result<String>
    suspend fun getFileSha(owner: String, repo: String, path: String): Result<String?>
    suspend fun pushFile(owner: String, repo: String, path: String, content: String, sha: String?, message: String): Result<Unit>
    suspend fun deleteFile(owner: String, repo: String, path: String, sha: String, message: String): Result<Unit>
    suspend fun getGitTree(owner: String, repo: String, sha: String): Result<GitTreeResponse>
}
