package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "moods",
    indices = [
        Index(value = ["date"])
    ]
)
data class MoodEntity(
    @PrimaryKey val timestamp: Long, // Epoch Millis
    val date: String, // ISO Date "yyyy-MM-dd"
    val time: String, // "HH:mm"
    val score: Int,
    val label: String,
    val activities: String, // Comma separated list
    val note: String?
)
