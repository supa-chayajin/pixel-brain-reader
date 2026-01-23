package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus

@Entity(tableName = "habit_configs")
data class HabitConfigEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val frequency: String, // JSON list or comma separated
    val targetValue: Double,
    val unit: String,
    val type: String = "BOOLEAN",
    val color: String = "#FF5722"
)

@Entity(tableName = "habit_logs", primaryKeys = ["habitId", "date"])
data class HabitLogEntity(
    val habitId: String,
    val date: String, // ISO Date "yyyy-MM-dd"
    val value: Double,
    val status: HabitStatus // SKIPPED, PARTIAL, COMPLETED
)
