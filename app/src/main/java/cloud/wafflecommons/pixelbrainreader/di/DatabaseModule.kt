package cloud.wafflecommons.pixelbrainreader.di

import android.content.Context
import androidx.room.Room
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pixel_brain_db"
        )
        // .openHelperFactory(...) // Logic for SQLCipher Phase A
        .fallbackToDestructiveMigration() // Default for development, Refined for Prod
        .build()
    }

    @Provides
    @Singleton
    fun provideFileDao(database: AppDatabase): FileDao {
        return database.fileDao()
    }

    @Provides
    @Singleton
    fun provideMetadataDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.SyncMetadataDao {
        return database.metadataDao()
    }

    @Provides
    @Singleton
    fun provideFileContentDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao {
        return database.fileContentDao()
    }
}
