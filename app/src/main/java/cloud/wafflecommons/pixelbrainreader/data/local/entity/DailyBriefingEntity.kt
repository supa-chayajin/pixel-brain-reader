package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caches the AI-generated Daily Briefing and Oracle Insight for a specific date.
 * Acts as a single source of truth to avoid re-generating content multiple times per day.
 */
@Entity(tableName = "daily_briefings")
data class DailyBriefingEntity(
    @PrimaryKey
    val date: String, // ISO Format: YYYY-MM-DD
    
    val briefingContent: String,
    val oracleContent: String?,
    
    val generatedAt: Long = System.currentTimeMillis()
)
