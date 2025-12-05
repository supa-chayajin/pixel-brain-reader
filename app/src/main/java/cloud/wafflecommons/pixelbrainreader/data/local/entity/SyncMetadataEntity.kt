package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val path: String, // The relative path, e.g., "" or "folder/subfolder"
    val etag: String,
    val lastSyncedAt: Long = System.currentTimeMillis()
)
