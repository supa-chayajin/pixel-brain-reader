package cloud.wafflecommons.pixelbrainreader.data.remote.model

import com.google.gson.annotations.SerializedName

data class RemoteFile(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int,
    val url: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("git_url") val gitUrl: String,
    @SerializedName("download_url") val downloadUrl: String?,
    val type: String,
    @SerializedName("_links") val links: Links? = null
)

data class Links(
    val self: String,
    val git: String,
    val html: String
)
