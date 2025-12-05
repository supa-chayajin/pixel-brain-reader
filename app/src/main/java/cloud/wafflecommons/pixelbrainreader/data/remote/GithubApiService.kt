package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

import retrofit2.Response
import retrofit2.http.Header

interface GithubApiService {
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String = "",
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<List<GithubFileDto>>

    @GET
    suspend fun getFileContent(@Url url: String): okhttp3.ResponseBody
}
