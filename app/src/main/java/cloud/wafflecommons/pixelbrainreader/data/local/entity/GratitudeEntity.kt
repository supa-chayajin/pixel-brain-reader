package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "daily_gratitudes")
data class GratitudeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String, // ISO-8601 YYYY-MM-DD
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
