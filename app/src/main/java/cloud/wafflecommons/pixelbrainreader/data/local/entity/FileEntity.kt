package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val path: String, // Path is unique to the file structure
    val name: String,
    val type: String, // "file" or "dir"
    val downloadUrl: String?,
    val sha: String? = null, // Remote SHA for incremental sync
    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false,
    val localModifiedTimestamp: Long? = null,
    val tags: String? = null, // Comma separated tags
    val rawMetadata: String? = null // JSON blob of other frontmatter
)

fun GithubFileDto.toEntity() = FileEntity(
    path = path,
    name = name,
    type = type,
    downloadUrl = downloadUrl,
    sha = sha,
    tags = null,
    rawMetadata = null
)

fun cloud.wafflecommons.pixelbrainreader.data.remote.model.RemoteFile.toEntity() = FileEntity(
    path = path,
    name = name,
    type = type,
    downloadUrl = downloadUrl,
    sha = sha, // Assuming RemoteFile also has sha, if not valid check needed.
    tags = null,
    rawMetadata = null
)
