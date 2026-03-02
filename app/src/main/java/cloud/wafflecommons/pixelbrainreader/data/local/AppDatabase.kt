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
        cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBriefingEntity::class,
        cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity::class // RFC-009
    ], 
    version = 19, // Incremented version
    exportSchema = false
)
@androidx.room.TypeConverters(RoomTypeConverters::class)
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
    abstract fun dailyBriefingDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyBriefingDao
    abstract fun gratitudeDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.GratitudeDao
    abstract fun taskDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
}
