package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.BuildConfig
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
            // BODY logs full request/response — including the OpenMeteo lat/long query
            // params (the user's GPS location). Never ship that in a release build; the
            // interceptor logs at INFO, which R8 does NOT strip.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
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

    @Provides
    @Singleton
    @javax.inject.Named("GitHubAuthRetrofit")
    fun provideGitHubAuthRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://github.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGitHubDeviceAuthService(@javax.inject.Named("GitHubAuthRetrofit") retrofit: Retrofit): cloud.wafflecommons.pixelbrainreader.data.auth.GitHubDeviceAuthService {
        return retrofit.create(cloud.wafflecommons.pixelbrainreader.data.auth.GitHubDeviceAuthService::class.java)
    }
}
