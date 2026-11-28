package cloud.wafflecommons.pixelbrainreader.di

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.watch.pictures.FeedImageLoaders
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The feeds pictures' Coil loader (13.4, F7), passed explicitly: never Coil's app-wide default. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedPictureLoader

@Module
@InstallIn(SingletonComponent::class)
object FeedPicturesModule {

    /** One loader for every feed picture: the ledgered client, its own memory and disk caches. */
    @Provides
    @Singleton
    @FeedPictureLoader
    fun imageLoader(@ApplicationContext context: Context): ImageLoader = FeedImageLoaders.build(context)
}

/**
 * The loader for composables, which Hilt doesn't inject (`FeedThumbnail`'s `rememberFeedImageLoader`).
 * Its getter has a name of its own: the singleton component implements every entry point, and
 * [GamePicturesEntryPoint] already has an `imageLoader()`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedPicturesEntryPoint {
    @FeedPictureLoader
    fun feedImageLoader(): ImageLoader
}
