package cloud.wafflecommons.pixelbrainreader.data.games.pictures

import cloud.wafflecommons.pixelbrainreader.data.games.GamePictures
import cloud.wafflecommons.pixelbrainreader.data.privacy.LedgerCall
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.privacy.TrafficCount
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
import java.net.UnknownHostException
import java.time.LocalDate

/**
 * 13.4 (GP2, the audit's coil-covers-bypass-ledger): the games pictures' client. A last application
 * interceptor answers canned responses, so nothing reaches the network; it counts what the guard
 * let through.
 */
class GameImageClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() {
        PrivacyLedger.uninstall()
    }

    private val png = "image/png".toMediaType()
    private val picture = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val tower = "https://image.api.playstation.com/vulcan/a/tower.png"

    /** Answers each request with what [answer] says; [seen] is every address that got through. */
    private class Canned(private val answer: (Request) -> Response.Builder) : Interceptor {
        val seen = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request.url.toString()
            return answer(request).request(request).protocol(Protocol.HTTP_1_1).message("canned").build()
        }
    }

    private val publicDns: Dns = fixedDns(byteArrayOf(8, 8, 8, 8))

    /** A resolver that answers every name with [addresses], without DNS. */
    private fun fixedDns(vararg addresses: ByteArray): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.map { InetAddress.getByAddress(hostname, it) }
    }

    private fun client(canned: Canned): OkHttpClient = GameImageClient.builder(dns = publicDns).addInterceptor(canned).build()

    private fun image(body: ByteArray = picture) = Response.Builder().code(200).body(body.toResponseBody(png))

    private fun redirect(to: String, code: Int = 302) =
        Response.Builder().code(code).header("Location", to).body(ByteArray(0).toResponseBody(null))

    private fun get(client: OkHttpClient, url: String, cache: CacheControl? = null): ByteArray {
        val request = Request.Builder().url(url).apply { cache?.let { cacheControl(it) } }.build()
        return client.newCall(request).execute().use { it.body!!.bytes() }
    }

    @Test
    fun `an http address never loads`() {
        val canned = Canned { image() }
        assertThrows(IOException::class.java) { get(client(canned), "http://image.api.playstation.com/vulcan/a/tower.png") }
        assertThrows(IOException::class.java) { get(client(canned), "http://store-images.s-microsoft.com/image/apps.1") }
        assertTrue(canned.seen.isEmpty())
    }

    @Test
    fun `a refused host never loads`() {
        val canned = Canned { image() }
        assertThrows(IOException::class.java) { get(client(canned), "https://images.example.com/tower.png") }
        assertThrows(IOException::class.java) { get(client(canned), "https://images-eds.xboxlive.com/image?url=abc") }
        assertThrows(IOException::class.java) { get(client(canned), "https://user@image.api.playstation.com/tower.png") }
        assertThrows(IOException::class.java) { get(client(canned), "https://10.0.0.1/tower.png") }
        assertTrue(canned.seen.isEmpty())
    }

    @Test
    fun `an allowed picture loads`() {
        val canned = Canned { image() }
        assertArrayEquals(picture, get(client(canned), tower))
        assertEquals(listOf(tower), canned.seen)
    }

    @Test
    fun `a redirect to a refused host or to http never loads`() {
        val toForeign = Canned { request ->
            if (request.url.host == "image.api.playstation.com") redirect("https://images.example.com/tower.png") else image()
        }
        assertThrows(IOException::class.java) { get(client(toForeign), tower) }
        assertEquals(listOf(tower), toForeign.seen)

        val toHttp = Canned { request -> if (request.url.scheme == "https") redirect("http://image.api.playstation.com/vulcan/a/tower.png") else image() }
        assertThrows(IOException::class.java) { get(client(toHttp), tower) }
        assertEquals(listOf(tower), toHttp.seen)

        val toNowhere = Canned { Response.Builder().code(301).body(ByteArray(0).toResponseBody(null)) }
        assertThrows(IOException::class.java) { get(client(toNowhere), tower) }
        assertEquals(listOf(tower), toNowhere.seen)
    }

    @Test
    fun `a redirect on an allowed host is followed hop by hop`() {
        val canned = Canned { request ->
            when (request.url.encodedPath) {
                "/vulcan/a/tower.png" -> redirect("/vulcan/b/tower.png", code = 301)
                "/vulcan/b/tower.png" -> redirect("https://store-images.s-microsoft.com/image/apps.1234.tower", code = 307)
                else -> image()
            }
        }
        assertArrayEquals(picture, get(client(canned), tower))
        assertEquals(
            listOf(tower, "https://image.api.playstation.com/vulcan/b/tower.png", "https://store-images.s-microsoft.com/image/apps.1234.tower"),
            canned.seen
        )
    }

    @Test
    fun `more than three hops stop`() {
        fun hops(count: Int) = Canned { request ->
            val hop = request.url.queryParameter("hop")?.toInt() ?: 0
            if (hop < count) redirect("/vulcan/a/tower.png?hop=${hop + 1}") else image()
        }
        val three = hops(GamePictures.MAX_REDIRECTS)
        assertArrayEquals(picture, get(client(three), tower))
        assertEquals(4, three.seen.size)

        val four = hops(GamePictures.MAX_REDIRECTS + 1)
        assertThrows(IOException::class.java) { get(client(four), tower) }
        // The fourth redirect is never followed.
        assertEquals(4, four.seen.size)
    }

    @Test
    fun `an answer above the cap never loads, with or without a length`() {
        val declared = Canned { image(byteArrayOf(1)).header("Content-Length", (GamePictures.MAX_BYTES + 1).toString()) }
        assertThrows(IOException::class.java) { get(client(declared), tower) }

        val streamed = Canned { Response.Builder().code(200).body(Zeros(GamePictures.MAX_BYTES + 1, png)) }
        assertThrows(IOException::class.java) { get(client(streamed), tower) }

        // Exactly the cap still loads.
        val atCap = Canned { Response.Builder().code(200).body(Zeros(GamePictures.MAX_BYTES, png)) }
        assertEquals(GamePictures.MAX_BYTES.toInt(), get(client(atCap), tower).size)
    }

    @Test
    fun `a page that isn't an image never loads`() {
        val html = Canned { Response.Builder().code(200).body("<html>blocked</html>".toResponseBody("text/html; charset=utf-8".toMediaType())) }
        assertThrows(IOException::class.java) { get(client(html), tower) }
        val garbled = Canned { Response.Builder().code(200).header("Content-Type", "not a type").body(picture.toResponseBody(null)) }
        assertThrows(IOException::class.java) { get(client(garbled), tower) }

        // A picture, bytes without a type, or no type at all still load.
        val webp = Canned { Response.Builder().code(200).body(picture.toResponseBody("image/webp".toMediaType())) }
        assertArrayEquals(picture, get(client(webp), tower))
        val octets = Canned { Response.Builder().code(200).body(picture.toResponseBody("application/octet-stream".toMediaType())) }
        assertArrayEquals(picture, get(client(octets), tower))
        val untyped = Canned { Response.Builder().code(200).body(picture.toResponseBody(null)) }
        assertArrayEquals(picture, get(client(untyped), tower))
    }

    @Test
    fun `a cache-only request makes no call`() {
        val canned = Canned { image() }
        assertThrows(IOException::class.java) { get(client(canned), tower, CacheControl.FORCE_CACHE) }
        assertTrue(canned.seen.isEmpty())
    }

    @Test
    fun `a private address is refused by the dns`() {
        fun dns(vararg addresses: ByteArray) = PublicOnlyDns(fixedDns(*addresses))
        val host = "image.api.playstation.com"
        assertThrows(UnknownHostException::class.java) { dns(byteArrayOf(10, 0, 0, 1)).lookup(host) }
        assertThrows(UnknownHostException::class.java) { dns(byteArrayOf(127, 0, 0, 1)).lookup(host) }
        assertThrows(UnknownHostException::class.java) { dns(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)).lookup(host) }
        // One private address among public ones is enough to refuse the name.
        assertThrows(UnknownHostException::class.java) { dns(byteArrayOf(8, 8, 8, 8), byteArrayOf(10, 0, 0, 1)).lookup(host) }
        assertThrows(UnknownHostException::class.java) { dns().lookup(host) }
        val ipv6Loopback = ByteArray(16).also { it[15] = 1 }
        assertThrows(UnknownHostException::class.java) { dns(ipv6Loopback).lookup(host) }
    }

    @Test
    fun `a public address passes the dns`() {
        val addresses = PublicOnlyDns(publicDns).lookup("image.api.playstation.com")
        assertEquals(listOf("8.8.8.8"), addresses.map { it.hostAddress })
    }

    @Test
    fun `the client follows no redirect itself, keeps no http cache and resolves through the public dns`() {
        val client = GameImageClient.build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertTrue(client.dns is PublicOnlyDns)
        assertTrue(client.interceptors.any { it is GamePictureGuard })
        assertEquals(45_000, client.callTimeoutMillis)
        assertTrue(GameImageClient.builder(dns = publicDns).build().dns === publicDns)
    }

    @Test
    fun `each picture call is in the privacy ledger under Game pictures`() = runTest {
        val dir = File(folder.root, "privacy_ledger")
        PrivacyLedger.install(dir, backgroundScope, { TrafficCount(0, 0) }, { 0L }, { LocalDate.of(2026, 11, 28) })
        val canned = Canned { image() }

        get(client(canned), tower)
        get(client(canned), tower)

        // Named and counted, never left in the unnamed pool or in another call's share.
        val calls = PrivacyLedger.days().first().calls
        assertEquals(listOf(LedgerCall("image.api.playstation.com", "game_pictures", 2, 0, 0)), calls)
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
