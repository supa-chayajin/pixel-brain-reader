package cloud.wafflecommons.pixelbrainreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.SyncMetadataDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.SyncMetadataEntity

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity

@Database(entities = [FileEntity::class, SyncMetadataEntity::class, FileContentEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun metadataDao(): SyncMetadataDao
    abstract fun fileContentDao(): FileContentDao
    
    // Security Check: This class will eventually be instantiated with a SupportFactory for SQLCipher.
    // For Phase B Step 1, we use standard Room builder but keep exportSchema=false to prevent schema leaks.
}
