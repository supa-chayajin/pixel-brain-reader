package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_contents")
data class FileContentEntity(
    @PrimaryKey val path: String,
    val content: String,
    val lastSyncedAt: Long = System.currentTimeMillis()
)
