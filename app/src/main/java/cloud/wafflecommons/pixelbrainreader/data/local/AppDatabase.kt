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
        cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity::class // V4.2 Neural Briefing
    ], 
    version = 9, 
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun metadataDao(): SyncMetadataDao
    abstract fun fileContentDao(): FileContentDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun newsDao(): NewsDao
}

class Converters {
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
