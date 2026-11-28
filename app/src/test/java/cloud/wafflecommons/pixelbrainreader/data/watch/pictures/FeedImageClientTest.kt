package cloud.wafflecommons.pixelbrainreader.data.watch.pictures

import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureGuard
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.PublicOnlyDns
import cloud.wafflecommons.pixelbrainreader.data.privacy.LedgerCall
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.privacy.TrafficCount
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedPictures
import kotlinx.coroutines.test.runTest
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.LocalDate

/**
 * 13.4 (F7): the feeds pictures' client, the games' guard with the feeds' rules. A last application
 * interceptor answers canned responses, so nothing reaches the network; it counts what the guard
 * let through.
 */
class FeedImageClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() {
        PrivacyLedger.uninstall()
    }

    private val jpeg = "image/jpeg".toMediaType()
    private val picture = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70)
    private val thumbnail = "https://cdn.example.org/thumbs/1.jpg"

    /** Answers each request with what [answer] says; [seen] is every address that got through. */
    private class Canned(private val answer: (Request) -> Response.Builder) : Interceptor {
        val seen = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request.url.toString()
            return answer(request).request(request).protocol(Protocol.HTTP_1_1).message("canned").build()
        }
    }

    /** A documentation address (TEST-NET-3): public, and nobody's. */
    private val publicDns: Dns = fixedDns(byteArrayOf(203.toByte(), 0, 113, 10))

    /** A resolver that answers every name with [addresses], without DNS. */
    private fun fixedDns(vararg addresses: ByteArray): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.map { InetAddress.getByAddress(hostname, it) }
    }

    private fun client(canned: Canned): OkHttpClient = FeedImageClient.builder(dns = publicDns).addInterceptor(canned).build()

    private fun image(body: ByteArray = picture) = Response.Builder().code(200).body(body.toResponseBody(jpeg))

    private fun redirect(to: String, code: Int = 302) =
        Response.Builder().code(code).header("Location", to).body(ByteArray(0).toResponseBody(null))

    private fun get(client: OkHttpClient, url: String, cache: CacheControl? = null): ByteArray {
        val request = Request.Builder().url(url).apply { cache?.let { cacheControl(it) } }.build()
        return client.newCall(request).execute().use { it.body!!.bytes() }
    }

    @Test
    fun `an http address never loads`() {
        val canned = Canned { image() }
        assertThrows(IOException::class.java) { get(client(canned), "http://cdn.example.org/thumbs/1.jpg") }
        assertThrows(IOException::class.java) { get(client(canned), "http://news.example/img/2.png") }
        assertTrue(canned.seen.isEmpty())
    }

    @Test
    fun `a private address never loads, written or resolved`() {
        // Written: refused before anything is asked.
        val canned = Canned { image() }
        listOf("https://10.0.0.1/a.png", "https://[::1]/a.png", "https://192.168.1.20/a.png", "https://localhost/a.png").forEach { url ->
            assertThrows(url, IOException::class.java) { get(client(canned), url) }
        }
        assertTrue(canned.seen.isEmpty())

        // Resolved: a public-looking name the resolver points at the LAN connects nowhere. No canned
        // answer here: the call goes as far as the name's lookup, which refuses it before any socket.
        val lan = PublicOnlyDns(fixedDns(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)))
        val toLan = FeedImageClient.builder(dns = lan).proxy(Proxy.NO_PROXY).build()
        assertThrows(IOException::class.java) { get(toLan, "https://pictures.example.org/a.png") }
        assertThrows(UnknownHostException::class.java) { lan.lookup("pictures.example.org") }
    }

    @Test
    fun `an answer above the cap never loads, with or without a length`() {
        val declared = Canned { image(byteArrayOf(1)).header("Content-Length", (FeedPictures.MAX_BYTES + 1).toString()) }
        assertThrows(IOException::class.java) { get(client(declared), thumbnail) }

        val streamed = Canned { Response.Builder().code(200).body(Zeros(FeedPictures.MAX_BYTES + 1, jpeg)) }
        assertThrows(IOException::class.java) { get(client(streamed), thumbnail) }

        // Exactly the cap still loads.
        val atCap = Canned { Response.Builder().code(200).body(Zeros(FeedPictures.MAX_BYTES, jpeg)) }
        assertEquals(FeedPictures.MAX_BYTES.toInt(), get(client(atCap), thumbnail).size)
        assertEquals(3L * 1024 * 1024, FeedPictures.MAX_BYTES)
    }

    @Test
    fun `a public https picture on any host loads`() {
        val canned = Canned { image() }
        val addresses = listOf(thumbnail, "https://news.example/img/2.png", "https://pictures.example.com/3.webp?w=200")
        addresses.forEach { assertArrayEquals(picture, get(client(canned), it)) }
        assertEquals(addresses, canned.seen)
    }

    @Test
    fun `a redirect to http or to a private address never loads`() {
        val toHttp = Canned { request -> if (request.url.scheme == "https") redirect("http://cdn.example.org/thumbs/1.jpg") else image() }
        assertThrows(IOException::class.java) { get(client(toHttp), thumbnail) }
        assertEquals(listOf(thumbnail), toHttp.seen)

        val toPrivate = Canned { request -> if (request.url.host == "cdn.example.org") redirect("https://10.0.0.1/thumbs/1.jpg") else image() }
        assertThrows(IOException::class.java) { get(client(toPrivate), thumbnail) }
        assertEquals(listOf(thumbnail), toPrivate.seen)

        val toNowhere = Canned { Response.Builder().code(301).body(ByteArray(0).toResponseBody(null)) }
        assertThrows(IOException::class.java) { get(client(toNowhere), thumbnail) }
        assertEquals(listOf(thumbnail), toNowhere.seen)

        // Another public host over https is followed, hop by hop.
        val toCdn = Canned { request ->
            when (request.url.host) {
                "cdn.example.org" -> redirect("//pictures.example.com/1.jpg", code = 301)
                else -> image()
            }
        }
        assertArrayEquals(picture, get(client(toCdn), thumbnail))
        assertEquals(listOf(thumbnail, "https://pictures.example.com/1.jpg"), toCdn.seen)
    }

    @Test
    fun `more than three hops stop`() {
        fun hops(count: Int) = Canned { request ->
            val hop = request.url.queryParameter("hop")?.toInt() ?: 0
            if (hop < count) redirect("/thumbs/1.jpg?hop=${hop + 1}") else image()
        }
        val three = hops(FeedPictures.MAX_REDIRECTS)
        assertArrayEquals(picture, get(client(three), thumbnail))
        assertEquals(4, three.seen.size)

        val four = hops(FeedPictures.MAX_REDIRECTS + 1)
        assertThrows(IOException::class.java) { get(client(four), thumbnail) }
        // The fourth redirect is never followed.
        assertEquals(4, four.seen.size)
    }

    @Test
    fun `a page that isn't an image never loads`() {
        val html = Canned { Response.Builder().code(200).body("<html>blocked</html>".toResponseBody("text/html; charset=utf-8".toMediaType())) }
        assertThrows(IOException::class.java) { get(client(html), thumbnail) }
        val garbled = Canned { Response.Builder().code(200).header("Content-Type", "not a type").body(picture.toResponseBody(null)) }
        assertThrows(IOException::class.java) { get(client(garbled), thumbnail) }

        // A picture, bytes without a type, or no type at all still load.
        val webp = Canned { Response.Builder().code(200).body(picture.toResponseBody("image/webp".toMediaType())) }
        assertArrayEquals(picture, get(client(webp), thumbnail))
        val octets = Canned { Response.Builder().code(200).body(picture.toResponseBody("application/octet-stream".toMediaType())) }
        assertArrayEquals(picture, get(client(octets), thumbnail))
        val untyped = Canned { Response.Builder().code(200).body(picture.toResponseBody(null)) }
        assertArrayEquals(picture, get(client(untyped), thumbnail))
    }

    @Test
    fun `a cache-only request makes no call`() {
        val canned = Canned { image() }
        assertThrows(IOException::class.java) { get(client(canned), thumbnail, CacheControl.FORCE_CACHE) }
        assertTrue(canned.seen.isEmpty())
    }

    @Test
    fun `the client follows no redirect itself, keeps no http cache and resolves through the public dns`() {
        val client = FeedImageClient.build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertTrue(client.dns is PublicOnlyDns)
        assertTrue(client.interceptors.any { it is GamePictureGuard })
        assertEquals(1, client.interceptors.size)
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(45_000, client.callTimeoutMillis)
        assertTrue(FeedImageClient.builder(dns = publicDns).build().dns === publicDns)
    }

    @Test
    fun `each picture call is in the privacy ledger under Feed pictures`() = runTest {
        val dir = File(folder.root, "privacy_ledger")
        PrivacyLedger.install(dir, backgroundScope, { TrafficCount(0, 0) }, { 0L }, { LocalDate.of(2026, 11, 28) })
        val canned = Canned { image() }

        get(client(canned), thumbnail)
        get(client(canned), thumbnail)

        // Named and counted, never left in the unnamed pool or in another call's share.
        val calls = PrivacyLedger.days().first().calls
        assertEquals(listOf(LedgerCall("cdn.example.org", "feed_pictures", 2, 0, 0)), calls)
    }

    /** A body of [size] zero bytes that doesn't say its length, read as it streams. */
    private class Zeros(private val size: Long, private val type: MediaType) : ResponseBody() {
        private val stream: BufferedSource by lazy { ZeroSource(size).buffer() }

        override fun contentType(): MediaType = type

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = stream
    }

    private class ZeroSource(private var left: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (left <= 0) return -1
            val count = minOf(byteCount, left, 8_192L)
            sink.write(ByteArray(count.toInt()))
            left -= count
            return count
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
