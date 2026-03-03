package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.compose.runtime.Immutable
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus

@Immutable
@Entity(
    tableName = "habit_configs",
    indices = [
        Index(value = ["archived", "sortOrder"])
    ]
)
data class HabitConfigEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val frequency: List<String>, // Uses TypeConverter
    val icon: String = "check_circle",
    val color: String = "#FF5722",
    val type: String = "BOOLEAN",
    val targetValue: Double,
    val unit: String,
    val autoSource: String? = null,
    val createdDate: String = "",
    val archived: Boolean = false,
    val sortOrder: Int = 0
)

@Immutable
@Entity(
    tableName = "habit_logs", 
    primaryKeys = ["habitId", "date"],
    indices = [
        Index(value = ["date"]),
        Index(value = ["status"])
    ]
)
data class HabitLogEntity(
    val habitId: String,
    val date: String, // ISO Date "yyyy-MM-dd"
    val value: Double,
    val status: HabitStatus // SKIPPED, PARTIAL, COMPLETED
)
