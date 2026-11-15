package cloud.wafflecommons.pixelbrainreader.data.privacy

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

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
