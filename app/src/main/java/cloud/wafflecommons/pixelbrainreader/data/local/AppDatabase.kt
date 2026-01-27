package cloud.wafflecommons.pixelbrainreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.SyncMetadataDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.SyncMetadataEntity

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao

@Database(
    entities = [
        FileEntity::class, 
        SyncMetadataEntity::class, 
        FileContentEntity::class,
        EmbeddingEntity::class, // V4.0 Neural Vault
        cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity::class, // V4.2 Neural Briefing
        cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitLogEntity::class,
        // V5.0 Cortex Buffer / Autonomous Dashboard
        cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity::class
    ], 
    version = 17, 
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun metadataDao(): SyncMetadataDao
    abstract fun fileContentDao(): FileContentDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun newsDao(): NewsDao
    abstract fun moodDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.MoodDao
    abstract fun habitDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
    abstract fun dailyDashboardDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
    abstract fun scratchDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.ScratchDao
}

class Converters {
    private val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_TIME

    @androidx.room.TypeConverter
    fun fromLocalDate(value: java.time.LocalDate?): String? {
        return value?.format(formatter)
    }

    @androidx.room.TypeConverter
    fun toLocalDate(value: String?): java.time.LocalDate? {
        return if (value.isNullOrEmpty()) null else java.time.LocalDate.parse(value, formatter)
    }

    @androidx.room.TypeConverter
    fun fromLocalTime(value: java.time.LocalTime?): String? {
        return value?.format(timeFormatter)
    }

    @androidx.room.TypeConverter
    fun toLocalTime(value: String?): java.time.LocalTime? {
        return if (value.isNullOrEmpty()) null else java.time.LocalTime.parse(value, timeFormatter)
    }

    @androidx.room.TypeConverter
    fun fromFloatList(value: List<Float>?): String? {
        if (value == null) return null
        return value.joinToString(",")
    }

    @androidx.room.TypeConverter
    fun toFloatList(value: String?): List<Float>? {
        if (value == null || value.isEmpty()) return null
        return value.split(",").mapNotNull { it.toFloatOrNull() }
    }
}
