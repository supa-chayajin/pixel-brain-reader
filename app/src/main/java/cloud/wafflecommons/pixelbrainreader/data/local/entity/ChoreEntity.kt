package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Isolated entity for physical household maintenance (Home OS).
 * Distinct from Habits which are behavior-focused and daily-resetting.
 */
@Immutable
@Entity(tableName = "chores")
data class ChoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val roomId: String, // Explicit FK reference to home_rooms table
    val baseEffort: Int, // The XP reward
    val frequencyDays: Int, // E.g., every 7 days
    val lastDoneDate: String, // ISO-8601 e.g., "2026-03-09"
    val icon: String = "cleaning_services", // Material icon alias
    val createdAt: Long = System.currentTimeMillis() // Epoch for stable sorting
)
