package cloud.wafflecommons.pixelbrainreader.di

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GameImageLoaders
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The games pictures' Coil loader (13.4, GP2), passed explicitly: never Coil's app-wide default. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GamePictureLoader

@Module
@InstallIn(SingletonComponent::class)
object GamePicturesModule {

    /** One loader for every game picture: the ledgered client, its own memory and disk caches. */
    @Provides
    @Singleton
    @GamePictureLoader
    fun imageLoader(@ApplicationContext context: Context): ImageLoader = GameImageLoaders.build(context)
}

/** The loader for composables, which Hilt doesn't inject (`GamePicture`'s `rememberGameImageLoader`). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GamePicturesEntryPoint {
    @GamePictureLoader
    fun imageLoader(): ImageLoader
}
