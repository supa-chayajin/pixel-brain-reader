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

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFileMetadata(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<GithubFileDto>

    @GET
    suspend fun getFileContent(@Url url: String): okhttp3.ResponseBody

    @retrofit2.http.PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @retrofit2.http.Body body: Map<String, String>
    ): Response<Unit>
}
