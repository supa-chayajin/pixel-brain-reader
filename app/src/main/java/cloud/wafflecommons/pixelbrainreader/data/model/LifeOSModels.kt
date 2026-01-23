package cloud.wafflecommons.pixelbrainreader.data.model

import androidx.compose.runtime.Immutable
import java.time.LocalTime

enum class HabitType { BOOLEAN, MEASURABLE }
enum class HabitStatus { COMPLETED, PARTIAL, SKIPPED, FAILED }

@Immutable
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

@Immutable
data class HabitLogEntry(
    val habitId: String,
    val date: String, // ISO8601 Date String "YYYY-MM-DD"
    val value: Double,
    val status: HabitStatus,
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
data class Task(
    val lineIndex: Int, // CRITICAL for atomic updates
    val originalText: String,
    val isCompleted: Boolean,
    val time: LocalTime? = null,
    val cleanText: String
)

@Immutable
data class TimelineEvent(
    val time: LocalTime,
    val content: String,
    val originalLine: String
)

@Immutable
data class MarkdownLink(
    val title: String,
    val url: String
)

@Immutable
data class BriefingData(
    val weather: String, // E.g., "☀️ 25°C"
    val parentingAdvice: String,
    val moodStats: String, // Sparkline
    val quote: String,
    val news: List<MarkdownLink>
)
