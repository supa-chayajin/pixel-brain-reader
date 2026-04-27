package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Represents a task in the "Journal" or "LifeOS" section.
 */
@Entity(
    tableName = "daily_tasks",
    indices = [androidx.room.Index(value = ["scheduledDate"])]
)
data class DailyTaskEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val scheduledDate: String, // ISO-8601 "YYYY-MM-DD"
    val label: String,
    val scheduledTime: String? = null, // "14:00" stored as string for simplicity in this refactor
    val isDone: Boolean = false,
    val priority: Int = 1,
    val section: String = "Journal",
    val googleTaskId: String? = null, // ID from external provider (Google Tasks)
    val source: String = "Local" // "Local", "GoogleTasks", etc.
)
