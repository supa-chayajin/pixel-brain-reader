package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

interface GithubApiService {
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String = ""
    ): List<GithubFileDto>

    @GET
    suspend fun getFileContent(@Url url: String): okhttp3.ResponseBody
}
