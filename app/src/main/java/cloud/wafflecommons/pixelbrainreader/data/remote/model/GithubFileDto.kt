package cloud.wafflecommons.pixelbrainreader.data.remote.model

import com.google.gson.annotations.SerializedName

data class GithubFileDto(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("type") val type: String,
    @SerializedName("download_url") val downloadUrl: String?,
    @SerializedName("sha") val sha: String? = null
)
