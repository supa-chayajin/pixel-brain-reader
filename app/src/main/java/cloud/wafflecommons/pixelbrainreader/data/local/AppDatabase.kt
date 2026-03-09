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
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChoreDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity

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
        cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity::class, // RFC-009
        cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity::class, // V4.6.1 Home Config
        ChoreEntity::class // V4.6 Home OS
    ], 
    version = 21, // Version 21 for HomeRoomEntity and roomId FK
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
    abstract fun choreDao(): ChoreDao
    abstract fun homeRoomDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.HomeRoomDao
}
