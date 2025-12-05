package cloud.wafflecommons.pixelbrainreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity

@Database(entities = [FileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    
    // Security Check: This class will eventually be instantiated with a SupportFactory for SQLCipher.
    // For Phase B Step 1, we use standard Room builder but keep exportSchema=false to prevent schema leaks.
}
