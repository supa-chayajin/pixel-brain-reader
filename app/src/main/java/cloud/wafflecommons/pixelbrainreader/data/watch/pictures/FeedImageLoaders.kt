package cloud.wafflecommons.pixelbrainreader.data.watch.pictures

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * The feeds pictures' Coil loader (13.4, F7), shaped like the games': [FeedImageClient] for the
 * network, a memory cache of its own and a disk cache in `cacheDir/feed_pictures` (a cache the
 * system may clear; nothing to migrate). Cache headers are ignored: a thumbnail is downloaded the
 * first time it shows and kept. Never Coil's app-wide default (no `ImageLoaderFactory`): every feed
 * picture passes it explicitly, so it loads nothing the ledger doesn't see.
 */
object FeedImageLoaders {

    /** The disk cache's folder under the app's cache directory. */
    const val DISK_FOLDER = "feed_pictures"

    private const val DISK_BYTES = 32L * 1024 * 1024
    private const val MEMORY_SHARE = 0.05

    fun build(context: Context): ImageLoader {
        val app = context.applicationContext
        return ImageLoader.Builder(app)
            .okHttpClient { FeedImageClient.build() }
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(MEMORY_SHARE).build() }
            .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve(DISK_FOLDER)).maxSizeBytes(DISK_BYTES).build() }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
