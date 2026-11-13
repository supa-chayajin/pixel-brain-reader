package cloud.wafflecommons.pixelbrainreader.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
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
        cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentFtsEntity::class, // v11 full-text index
        cloud.wafflecommons.pixelbrainreader.data.local.entity.LinkEntity::class, // v11 links index
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
        ChoreEntity::class, // V4.6 Home OS
        cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity::class // Nano chat memory (RAG + CREATIVE)
    ],
    version = 32, // a habit's custody "Days" choice (MIGRATION_31_32)
    exportSchema = false
)
@androidx.room.TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun metadataDao(): SyncMetadataDao
    abstract fun fileContentDao(): FileContentDao
    abstract fun fileSearchDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.FileSearchDao
    abstract fun linkDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.LinkDao
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
    abstract fun chatDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.ChatDao

    companion object {
        /**
         * 31 → 32: the habit's custody "Days" column, added in place. The destructive fallback would
         * also drop the chat history (Room-only) and every vector (a full re-index on the phone).
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habit_configs ADD COLUMN custody TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
