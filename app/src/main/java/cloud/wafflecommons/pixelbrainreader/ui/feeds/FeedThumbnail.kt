package cloud.wafflecommons.pixelbrainreader.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedPictures
import cloud.wafflecommons.pixelbrainreader.di.FeedPicturesEntryPoint
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dagger.hilt.android.EntryPointAccessors

/** A headline's thumbnail on the Feeds tab (13.4, F7): decoded at [FeedPictures.SIZE_PX]. */
val FeedThumbnailSize: Dp = 56.dp

/**
 * The feeds pictures' loader (the privacy ledger's "Feed pictures": https on a public address
 * only, a size cap, every redirect checked), from the Hilt graph and remembered: never Coil's
 * app-wide default, so a feed picture loads nothing the ledger doesn't see.
 */
@Composable
fun rememberFeedImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, FeedPicturesEntryPoint::class.java).feedImageLoader()
    }
}

/**
 * A headline's picture (13.4, F7), on the Feeds tab only (Daily's block stays text): nothing when
 * [url] is null or its load fails (the slot goes, with its [modifier]'s margins); a
 * surfaceContainerHighest square while it loads; cropped in shapes.medium. Decorative: the row's
 * own text says the headline, so TalkBack reads nothing of it. No log (an address is never logged).
 */
@Composable
fun FeedThumbnail(url: String?, modifier: Modifier = Modifier, size: Dp = FeedThumbnailSize) {
    if (url == null) return
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    val context = LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context).data(url).size(FeedPictures.SIZE_PX).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = rememberFeedImageLoader(),
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentScale = ContentScale.Crop,
        onError = { failed = true }
    )
}
