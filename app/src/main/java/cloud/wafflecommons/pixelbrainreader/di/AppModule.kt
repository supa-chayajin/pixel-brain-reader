package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.remote.AuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // TokenManager and AuthInterceptor are already provided via @Inject constructor
    // but if we needed to provide third-party classes, we would do it here.
    // For now, this module might be empty or used for Retrofit later.
}
