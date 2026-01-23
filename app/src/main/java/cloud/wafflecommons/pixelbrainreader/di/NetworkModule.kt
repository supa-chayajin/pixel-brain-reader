package cloud.wafflecommons.pixelbrainreader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
            .build()
    }

    @Provides
    @Singleton
    @javax.inject.Named("OpenMeteoRetrofit")
    fun provideOpenMeteoRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenMeteoService(@javax.inject.Named("OpenMeteoRetrofit") retrofit: Retrofit): cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService {
        return retrofit.create(cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService::class.java)
    }
}
