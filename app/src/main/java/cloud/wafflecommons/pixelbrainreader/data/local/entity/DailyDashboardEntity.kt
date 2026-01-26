package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * The State Anchor for the Autonomous Dashboard.
 * Replaces the legacy DailyBufferEntity.
 */
@Entity(tableName = "daily_dashboard")
data class DailyDashboardEntity(
    @PrimaryKey
    val date: LocalDate, // Stored as ISO-8601 String via TypeConverter ("2026-01-26")
    
    // AI Caching
    val aiWeatherBriefing: String? = null,
    val aiQuoteOfTheDay: String? = null,
    val lastAiGenerationTimestamp: Long? = null, // Unix Epoch
    
    // Core Content
    val dailyMantra: String = "",
    
    // Second Brain / Persistent Sections
    val ideasContent: String = "", // "🧠 Idées / Second Cerveau"
    val notesContent: String = ""  // "📑 Notes / Self-care"
)
