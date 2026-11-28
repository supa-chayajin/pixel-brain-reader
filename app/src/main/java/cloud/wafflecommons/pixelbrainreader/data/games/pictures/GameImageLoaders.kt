package cloud.wafflecommons.pixelbrainreader.data.games.pictures

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * The games pictures' Coil loader (13.4, GP2): [GameImageClient] for the network, a memory cache of
 * its own and a disk cache in `cacheDir/game_pictures` (a cache the system may clear; nothing to
 * migrate). Cache headers are ignored (P4): a picture is downloaded the first time it shows and kept.
 * Never Coil's app-wide default (no `ImageLoaderFactory`): every game picture passes it explicitly,
 * so nothing else in the app loads through it and it loads nothing the ledger doesn't see.
 */
object GameImageLoaders {

    /** The disk cache's folder under the app's cache directory. */
    const val DISK_FOLDER = "game_pictures"

    private const val DISK_BYTES = 64L * 1024 * 1024
    private const val MEMORY_SHARE = 0.10

    fun build(context: Context): ImageLoader {
        val app = context.applicationContext
        return ImageLoader.Builder(app)
            .okHttpClient { GameImageClient.build() }
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(MEMORY_SHARE).build() }
            .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve(DISK_FOLDER)).maxSizeBytes(DISK_BYTES).build() }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
