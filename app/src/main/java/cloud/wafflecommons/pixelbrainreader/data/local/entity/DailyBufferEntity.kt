package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Root entity for a Daily Note Buffer.
 * Ensures we have a day-level anchor for foreign keys.
 */
@Entity(tableName = "daily_buffer")
data class DailyBufferEntity(
    @PrimaryKey
    val date: LocalDate,        // usage: 2026-01-26
    val mantra: String = "",    // "Don't you dare go hollow..."
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * Represents a chronological event in the "Timeline" section.
 */
@Entity(
    tableName = "timeline_entries",
    foreignKeys = [
        ForeignKey(
            entity = DailyBufferEntity::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["date"])]
)
data class TimelineEntryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,        // PK of Parent
    val time: LocalTime,
    val content: String,
    // Store original line to preserve formatting if needed during burn-back, 
    // or to detect if changed from external edit
    val originalMarkdown: String? = null 
)

/**
 * Represents a task in the "Journal" or "LifeOS" section.
 */
@Entity(
    tableName = "daily_tasks",
    foreignKeys = [
        ForeignKey(
            entity = DailyBufferEntity::class,
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
