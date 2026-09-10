package cloud.wafflecommons.pixelbrainreader.di

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
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
    fun provideGsonFactory(): JsonFactory = GsonFactory.getDefaultInstance()

    /**
     * Plain [NetHttpTransport]: the platform TLS trust store, cheap to create.
     * `GoogleNetHttpTransport.newTrustedTransport()` loads Google's bundled certificate KeyStore,
     * and this transport is created while the start screen's ViewModels are injected — on the main
     * thread, at cold start.
     */
    @Provides
    @Singleton
    fun provideHttpTransport(): HttpTransport = NetHttpTransport()
}
