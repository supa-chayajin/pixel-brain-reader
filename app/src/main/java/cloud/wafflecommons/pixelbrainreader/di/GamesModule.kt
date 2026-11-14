package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimelineSource
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.psn.OkHttpPsnTransport
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Games (12.17, RFC-009): the burn reads the day's game lines through [GameTimelineSource]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GamesModule {

    @Binds
    abstract fun gameTimelineSource(repository: GamesRepository): GameTimelineSource

    companion object {
        /** Sony's app client comes from local.properties (D10); without it PSN stays hidden. */
        @Provides
        @Singleton
        fun psnClient(): PsnClient = PsnClient(OkHttpPsnTransport(), BuildConfig.PSN_CLIENT_ID, BuildConfig.PSN_CLIENT_SECRET)
    }
}
