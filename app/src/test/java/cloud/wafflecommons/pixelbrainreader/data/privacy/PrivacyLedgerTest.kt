package cloud.wafflecommons.pixelbrainreader.data.privacy

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.time.LocalDate
import javax.net.SocketFactory

class PrivacyLedgerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private var sent = 0L
    private var received = 0L
    private var today = LocalDate.of(2026, 11, 15)

    @Before
    fun setUp() {
        dir = File(folder.root, "privacy_ledger")
    }

    @After
    fun tearDown() {
        PrivacyLedger.uninstall()
    }

    private fun kotlinx.coroutines.test.TestScope.install() {
        PrivacyLedger.install(dir, backgroundScope, { TrafficCount(sent, received) }, { 0L }, { today })
    }

    @Test
    fun `off, a measured block only runs`() {
        assertEquals(42, PrivacyLedger.measured("github.com", NetPurpose.SYNC) { 42 })
        assertEquals(0L, PrivacyLedger.begin("github.com", NetPurpose.SYNC))
    }

    @Test
    fun `a measured call lands in today's file`() = runTest {
        install()
        PrivacyLedger.measured("github.com", NetPurpose.SYNC) {
            sent += 300
            received += 4_000
        }
        val days = PrivacyLedger.days()
        assertEquals("2026-11-15", days.first().date)
        assertEquals(listOf(LedgerCall("github.com", "sync", 1, 300, 4_000)), days.first().calls)
        val stored = File(dir, "2026-11-15.json").readText()
        assertTrue(stored.contains("\"github.com\""))
    }

    @Test
    fun `a block that throws is still counted`() = runTest {
        install()
        runCatching {
            PrivacyLedger.measured("github.com", NetPurpose.SYNC) {
                sent += 10
                error("the push was refused")
            }
        }
        assertEquals(1, PrivacyLedger.days().first().calls.single().calls)
    }

    @Test
    fun `what ran with no call open is the day's unnamed traffic`() = runTest {
        install()
        received += 900
        val day = PrivacyLedger.days().first()
        assertEquals(900L, day.unnamedReceived)
        assertTrue(day.calls.isEmpty())
    }

    @Test
    fun `two writes of a day add up`() = runTest {
        install()
        PrivacyLedger.measured("github.com", NetPurpose.SYNC) { sent += 1 }
        PrivacyLedger.days()
        PrivacyLedger.measured("github.com", NetPurpose.SYNC) { sent += 2 }
        val row = PrivacyLedger.days().first().calls.single()
        assertEquals(2, row.calls)
        assertEquals(3L, row.sent)
    }

    @Test
    fun `earlier days follow today, newest first, and past the window they go`() = runTest {
        dir.mkdirs()
        File(dir, "2026-11-05.json").writeText("""{"date":"2026-11-05","calls":[{"host":"github.com","purpose":"sync","calls":1}]}""")
        File(dir, "2026-11-10.json").writeText("""{"date":"2026-11-10","calls":[]}""")
        File(dir, "2026-10-01.json").writeText("""{"date":"2026-10-01","calls":[]}""")
        install()
        val days = PrivacyLedger.days()
        assertEquals(listOf("2026-11-15", "2026-11-10", "2026-11-05"), days.map { it.date })
        assertFalse(File(dir, "2026-10-01.json").exists())
    }

    @Test
    fun `a day file that no longer reads is set aside`() = runTest {
        dir.mkdirs()
        File(dir, "2026-11-15.json").writeText("{ not json")
        install()
        PrivacyLedger.measured("github.com", NetPurpose.SYNC) { sent += 5 }
        assertEquals(5L, PrivacyLedger.days().first().calls.single().sent)
        assertTrue(File(dir, "2026-11-15.json.bad").exists())
    }

    /** A call to [url] as an OkHttp client's listener sees it. */
    private fun callTo(url: String): Call = mockk { every { request() } returns Request.Builder().url(url).build() }

    /** A connection opened for [host] (its route's address). */
    private fun connectionTo(host: String): Connection = object : Connection {
        override fun route(): Route = Route(
            Address(
                host, 443, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null, Authenticator.NONE, null,
                listOf(Protocol.HTTP_1_1), listOf(ConnectionSpec.CLEARTEXT), ProxySelector.getDefault()
            ),
            Proxy.NO_PROXY,
            InetSocketAddress.createUnresolved(host, 443)
        )

        override fun socket(): Socket = Socket()

        override fun handshake(): Handshake? = null

        override fun protocol(): Protocol = Protocol.HTTP_1_1
    }

    @Test
    fun `a redirect to another host inside a call is that host's, each byte counted once`() = runTest {
        install()
        val call = callTo("https://news.example/pic.jpg")
        val listener = PrivacyLedger.events(NetPurpose.FEED_PICTURES).create(call)

        listener.callStart(call)
        listener.connectionAcquired(call, connectionTo("news.example"))
        sent += 300
        received += 120 // the redirect
        listener.connectionAcquired(call, connectionTo("cdn.example"))
        sent += 280
        received += 9_000 // the picture
        listener.callEnd(call)

        val day = PrivacyLedger.days().first()
        assertEquals(
            listOf(
                LedgerCall("news.example", "feed_pictures", 1, 300, 120),
                LedgerCall("cdn.example", "feed_pictures", 1, 280, 9_000)
            ),
            day.calls
        )
        assertEquals(0L, day.unnamedSent + day.unnamedReceived)
    }

    @Test
    fun `a call that stays on its host is one call, and a shared connection keeps the request's host`() = runTest {
        install()
        val call = callTo("https://img2.example/a.png")
        val listener = PrivacyLedger.events(NetPurpose.GAME_PICTURES).create(call)

        listener.callStart(call)
        // An HTTP/2 connection opened for a sibling host carries the call's own request.
        listener.connectionAcquired(call, connectionTo("img1.example"))
        received += 4_000
        listener.callEnd(call)

        assertEquals(listOf(LedgerCall("img2.example", "game_pictures", 1, 0, 4_000)), PrivacyLedger.days().first().calls)
    }

    @Test
    fun `a redirect on the shared client keeps the call's purpose`() = runTest {
        install()
        val call = callTo("https://github.com/login/device")
        val listener = PrivacyLedger.events(null).create(call)

        listener.callStart(call)
        listener.connectionAcquired(call, connectionTo("github.com"))
        received += 10
        listener.connectionAcquired(call, connectionTo("objects.example"))
        received += 20
        listener.callFailed(call, java.io.IOException("reset"))

        assertEquals(
            listOf(LedgerCall("github.com", "sign_in", 1, 0, 10), LedgerCall("objects.example", "sign_in", 1, 0, 20)),
            PrivacyLedger.days().first().calls
        )
    }

    @Test
    fun `the shared client's hosts say their purpose`() {
        assertEquals(NetPurpose.WEATHER, PrivacyLedger.purposeOf("api.open-meteo.com"))
        assertEquals(NetPurpose.SIGN_IN, PrivacyLedger.purposeOf("github.com"))
        assertEquals(NetPurpose.SIGN_IN, PrivacyLedger.purposeOf("api.github.com"))
        assertEquals(NetPurpose.OTHER, PrivacyLedger.purposeOf("example.org"))
    }

    @Test
    fun `only the host of an address is kept`() {
        assertEquals("www.googleapis.com", PrivacyLedger.hostOf("https://www.googleapis.com/calendar/v3?key=x"))
        assertEquals("github.com", PrivacyLedger.hostOf("https://GitHub.com/owner/vault.git"))
        assertEquals("github.com", PrivacyLedger.hostOf(null, fallback = "github.com"))
        assertEquals("unknown", PrivacyLedger.hostOf("not an address"))
    }
}
