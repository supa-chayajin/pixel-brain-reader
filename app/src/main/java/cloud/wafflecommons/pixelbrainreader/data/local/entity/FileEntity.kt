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
    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false,
    val localModifiedTimestamp: Long? = null
)

fun GithubFileDto.toEntity() = FileEntity(
    path = path,
    name = name,
    type = type,
    downloadUrl = downloadUrl
)
