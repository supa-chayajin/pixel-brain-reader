package cloud.wafflecommons.pixelbrainreader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            // Bound every phase so a stalled network can't hang the weather flow
            // indefinitely (callTimeout caps the whole call incl. redirects).
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @javax.inject.Named("OpenMeteoRetrofit")
    fun provideOpenMeteoRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Was building a default client with NO timeouts — wire in the shared,
        // timeout-bounded OkHttpClient so OpenMeteo calls fail fast instead of hanging.
        return Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenMeteoService(@javax.inject.Named("OpenMeteoRetrofit") retrofit: Retrofit): cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService {
        return retrofit.create(cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService::class.java)
    }
}
