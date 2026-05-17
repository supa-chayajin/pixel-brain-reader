package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Represents a chronological event in the "Timeline" section.
 */
@Entity(
    tableName = "timeline_entries",
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
data class TimelineEntryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,        // PK of Parent
    val time: LocalTime,
    val content: String,
    // Store original line to preserve formatting if needed during burn-back
    val originalMarkdown: String? = null,
    val googleEventId: String? = null, // For duplicate detection in Google Calendar sync
    // V6: outbox flags drained by CalendarSyncWorker
    val isDirty: Boolean = false,
    val pendingDeletion: Boolean = false
)
