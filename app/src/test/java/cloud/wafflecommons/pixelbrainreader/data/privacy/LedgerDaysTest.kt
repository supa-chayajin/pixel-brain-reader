package cloud.wafflecommons.pixelbrainreader.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LedgerDaysTest {

    private val empty = LedgerDay("2026-11-15")

    @Test
    fun `a second call to the same host and purpose adds to its row`() {
        val day = LedgerDays.add(
            LedgerDays.add(empty, "GitHub.com", NetPurpose.SYNC, sent = 100, received = 1_000),
            "github.com", NetPurpose.SYNC, sent = 50, received = 500
        )
        assertEquals(listOf(LedgerCall("github.com", "sync", calls = 2, sent = 150, received = 1_500)), day.calls)
    }

    @Test
    fun `a call without a reading keeps the day unmeasured`() {
        val day = LedgerDays.add(empty, "github.com", NetPurpose.SYNC, 0, 0, measured = false)
        assertFalse(day.measured)
        assertFalse(LedgerDays.add(day, "github.com", NetPurpose.SYNC, 1, 1).measured)
    }

    @Test
    fun `merging adds the rows and the unnamed traffic`() {
        val stored = LedgerDays.addUnnamed(LedgerDays.add(empty, "github.com", NetPurpose.SYNC, 10, 20), sent = 5, received = 6)
        val change = LedgerDays.addUnnamed(
            LedgerDays.add(LedgerDays.add(empty, "github.com", NetPurpose.SYNC, 1, 2), "api.open-meteo.com", NetPurpose.WEATHER, 3, 4),
            sent = 1, received = 1
        )
        val merged = LedgerDays.merge(stored, change)
        assertEquals(
            listOf(
                LedgerCall("github.com", "sync", calls = 2, sent = 11, received = 22),
                LedgerCall("api.open-meteo.com", "weather", calls = 1, sent = 3, received = 4)
            ),
            merged.calls
        )
        assertEquals(6L, merged.unnamedSent)
        assertEquals(7L, merged.unnamedReceived)
    }

    @Test
    fun `hosts come grouped, the most traffic first`() {
        var day = LedgerDays.add(empty, "github.com", NetPurpose.SIGN_IN, 1, 1)
        day = LedgerDays.add(day, "api.open-meteo.com", NetPurpose.WEATHER, 10, 10)
        day = LedgerDays.add(day, "github.com", NetPurpose.SYNC, 100, 1_000)
        val hosts = LedgerDays.byHost(day)
        assertEquals(listOf("github.com", "api.open-meteo.com"), hosts.map { it.host })
        assertEquals(listOf(NetPurpose.SYNC, NetPurpose.SIGN_IN), hosts.first().purposes)
        assertEquals(2, hosts.first().calls)
        assertEquals(101L, hosts.first().sent)
    }

    @Test
    fun `the totals count the unnamed traffic too`() {
        val day = LedgerDays.addUnnamed(LedgerDays.add(empty, "github.com", NetPurpose.SYNC, 10, 20), sent = 5, received = 6)
        assertEquals(15L, LedgerDays.sent(day))
        assertEquals(26L, LedgerDays.received(day))
        assertTrue(LedgerDays.isEmpty(empty))
        assertFalse(LedgerDays.isEmpty(LedgerDays.addUnnamed(empty, 1, 0)))
    }

    @Test
    fun `today and the 30 days before it stay`() {
        val today = LocalDate.of(2026, 11, 15)
        assertFalse(LedgerDays.expired(today.minusDays(30), today))
        assertTrue(LedgerDays.expired(today.minusDays(31), today))
    }

    @Test
    fun `an unknown purpose key reads as other`() {
        assertEquals(NetPurpose.SYNC, NetPurpose.of("sync"))
        assertEquals(NetPurpose.OTHER, NetPurpose.of("a-later-purpose"))
    }

    @Test
    fun `bytes read as people say them`() {
        assertEquals("999 B", LedgerDays.bytes(999))
        assertEquals("1 KB", LedgerDays.bytes(1_500))
        assertEquals("2.5 MB", LedgerDays.bytes(2_500_000))
        assertEquals("1.2 GB", LedgerDays.bytes(1_200_000_000))
    }
}
