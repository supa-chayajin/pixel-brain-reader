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
    foreignKeys = [
        ForeignKey(
            entity = DailyDashboardEntity::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["date"])]
)
data class DailyTaskEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val label: String,
    val scheduledTime: LocalTime? = null, // "14:00 Call"
    val isDone: Boolean = false,
    val priority: Int = 1, // 1=Normal, 2=High, 3=Critical
    val section: String = "Journal" // "Journal", "Ideas", etc.
)
