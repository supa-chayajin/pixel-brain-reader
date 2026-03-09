package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a physical/logical room in the household.
 */
@Immutable
@Entity(tableName = "home_rooms")
data class HomeRoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String = "home", // Default Material Icon
    val color: String = "#808080", // Default Hex color
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
