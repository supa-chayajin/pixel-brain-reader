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
        .fallbackToDestructiveMigration(true) // Default for development, Refined for Prod
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

    @Provides
    @Singleton
    fun provideEmbeddingDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao {
        return database.embeddingDao()
    }

    @Provides
    @Singleton
    fun provideNewsDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao {
        return database.newsDao()
    }
    
    @Provides
    @Singleton
    fun provideMoodDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.MoodDao {
        return database.moodDao()
    }
    
    @Provides
    @Singleton
    fun provideHabitDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao {
        return database.habitDao()
    }

    @Provides
    @Singleton
    fun provideDailyDashboardDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao {
        return database.dailyDashboardDao()
    }

    @Provides
    @Singleton
    fun provideScratchDao(database: AppDatabase): cloud.wafflecommons.pixelbrainreader.data.local.dao.ScratchDao {
        return database.scratchDao()
    }
}
