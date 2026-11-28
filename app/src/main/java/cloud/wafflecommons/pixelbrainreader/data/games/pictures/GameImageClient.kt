package cloud.wafflecommons.pixelbrainreader.data.games.pictures

import cloud.wafflecommons.pixelbrainreader.data.games.GamePictures
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The games pictures' own OkHttp client (13.4, GP2): every call in the privacy ledger as "Game
 * pictures", only the consoles' three image hosts over https ([GamePictures.allowed]), every
 * connection to a public address ([PublicOnlyDns]), at most [GamePictures.MAX_BYTES] a picture, and
 * redirects followed by hand, each hop checked before it is requested ([GamePictureGuard]). No
 * logging interceptor (a debug build would dump the bytes) and no OkHttp cache: Coil keeps the
 * pictures in its own disk cache.
 */
object GameImageClient {

    fun build(): OkHttpClient = builder().build()

    internal fun builder(dns: Dns = PublicOnlyDns()): OkHttpClient.Builder = OkHttpClient.Builder()
        .eventListenerFactory(PrivacyLedger.events(NetPurpose.GAME_PICTURES))
        // OkHttp's own follow-up would request a hop before it is checked: the guard follows them.
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(GamePictureGuard())
}

/**
 * What a picture's call may do (13.4): a cache-only request never reaches the network, an address
 * [GamePictures.allowed] refuses is never requested, a redirect is followed only to an allowed
 * address (at most [GamePictures.MAX_REDIRECTS] hops, each as a GET), and the answer must be an
 * image (or bytes without a type) of at most [maxBytes], counted as it is read. Every refusal is an
 * [IOException] with fixed words, never the address.
 */
internal class GamePictureGuard(private val maxBytes: Long = GamePictures.MAX_BYTES) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.cacheControl.onlyIfCached) throw IOException("Only from the cache")
        if (!GamePictures.allowed(request.url.toString())) throw IOException("Refused")
        var response = chain.proceed(request)
        var hops = 0
        while (response.code in REDIRECTS) {
            if (hops >= GamePictures.MAX_REDIRECTS) {
                response.close()
                throw IOException("Too many redirects")
            }
            val next = GamePictures.redirectTarget(request.url.toString(), response.header("Location"))
            response.close()
            if (next == null) throw IOException("Redirect refused")
            request = request.newBuilder().url(next).get().build()
            response = chain.proceed(request)
            hops++
        }
        return checked(response)
    }

    private fun checked(response: Response): Response {
        val body = response.body ?: return response
        val declared = response.header("Content-Length")?.trim()?.toLongOrNull() ?: body.contentLength()
        if (declared > maxBytes) {
            response.close()
            throw IOException("Too large")
        }
        // The header as sent (one that doesn't parse is no picture), else the type the body carries.
        val header = response.header("Content-Type")
        val type = if (header != null) header.toMediaTypeOrNull() else body.contentType()
        if ((header != null || type != null) && !isPicture(type)) {
            response.close()
            throw IOException("Not a picture")
        }
        return response.newBuilder().body(CappedBody(body, maxBytes)).build()
    }

    private fun isPicture(type: MediaType?): Boolean =
        type != null && (type.type == "image" || (type.type == "application" && type.subtype == "octet-stream"))

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** A body that fails once more than [maxBytes] were read from it, whatever its length said. */
private class CappedBody(private val delegate: ResponseBody, private val maxBytes: Long) : ResponseBody() {
    private val capped: BufferedSource by lazy { CappedSource(delegate.source(), maxBytes).buffer() }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = capped
}

private class CappedSource(delegate: Source, private val maxBytes: Long) : ForwardingSource(delegate) {
    private var total = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) {
            total += read
            if (total > maxBytes) throw IOException("Too large")
        }
        return read
    }
}

/**
 * The names [delegate] resolves, refused unless every address is public
 * ([ImportUrlValidator.isPublicAddress]): a console host made to point at the LAN loads nothing.
 */
internal class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !ImportUrlValidator.isPublicAddress(it) }) {
            throw UnknownHostException("Not a public address")
        }
        return addresses
    }
}
