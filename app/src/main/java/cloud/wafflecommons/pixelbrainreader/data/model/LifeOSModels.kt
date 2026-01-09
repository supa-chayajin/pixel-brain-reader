package cloud.wafflecommons.pixelbrainreader.data.model

import java.time.LocalTime

enum class HabitType { BOOLEAN, MEASURABLE }
enum class HabitStatus { COMPLETED, PARTIAL, SKIPPED, FAILED }

data class HabitConfig(
    val id: String,
    val title: String,
    val description: String = "",
    val frequency: List<String> = emptyList(), // "MON", "TUE"...
    val icon: String = "check_circle", // Material Icon Name
    val color: String = "#FF5722",
    val type: HabitType = HabitType.BOOLEAN,
    val targetValue: Double = 0.0,
    val unit: String = ""
)

data class HabitLogEntry(
    val habitId: String,
    val date: String, // ISO8601 Date String "YYYY-MM-DD"
    val value: Double,
    val status: HabitStatus,
    val timestamp: Long = System.currentTimeMillis()
)

data class Task(
    val lineIndex: Int, // CRITICAL for atomic updates
    val originalText: String,
    val isCompleted: Boolean,
    val time: LocalTime? = null,
    val cleanText: String
)
