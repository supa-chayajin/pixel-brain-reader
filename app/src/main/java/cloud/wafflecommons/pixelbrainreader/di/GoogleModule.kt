package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.tasks.Tasks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GoogleModule {

    @Provides
    @Singleton
    fun provideGsonFactory(): com.google.api.client.json.JsonFactory = GsonFactory.getDefaultInstance()

    @Provides
    @Singleton
    fun provideHttpTransport(): com.google.api.client.http.HttpTransport = GoogleNetHttpTransport.newTrustedTransport()

    // These will be used by the Repository to build authorized clients
    // In a real app, we would use a specialized factory that takes the current token.
}
