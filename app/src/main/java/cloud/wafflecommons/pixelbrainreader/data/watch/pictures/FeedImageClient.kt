package cloud.wafflecommons.pixelbrainreader.data.watch.pictures

import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureGuard
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.PublicOnlyDns
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedPictures
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The feeds pictures' own OkHttp client (13.4, F7): the games' guard with the feeds' rules, in the
 * privacy ledger as "Feed pictures". Only https addresses on public hosts ([FeedPictures.allowed]),
 * every connection to a public address ([PublicOnlyDns]), at most [FeedPictures.MAX_BYTES] a
 * picture, and redirects followed by hand, each hop checked before it is requested. No logging
 * interceptor (a debug build would dump the bytes) and no OkHttp cache: Coil keeps the pictures in
 * its own disk cache.
 */
object FeedImageClient {

    fun build(): OkHttpClient = builder().build()

    internal fun builder(dns: Dns = PublicOnlyDns()): OkHttpClient.Builder = OkHttpClient.Builder()
        .eventListenerFactory(PrivacyLedger.events(NetPurpose.FEED_PICTURES))
        // OkHttp's own follow-up would request a hop before it is checked: the guard follows them.
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(
            GamePictureGuard(FeedPictures.MAX_BYTES, FeedPictures::allowed, FeedPictures::redirectTarget, FeedPictures.MAX_REDIRECTS)
        )
}
