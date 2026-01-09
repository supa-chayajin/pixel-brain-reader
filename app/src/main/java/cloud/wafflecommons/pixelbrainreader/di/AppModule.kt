package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.remote.AuthInterceptor
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideHabitRepository(
        fileRepository: cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository,
        gson: Gson,
        secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
    ): cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository {
        return cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository(fileRepository, gson, secretManager)
    }

    @Provides
    @Singleton
    fun provideTaskRepository(
        fileRepository: cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository,
        secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
    ): cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository {
        return cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository(fileRepository, secretManager)
    }
}
