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
    fun `the Xbox purpose reads back from its key, PlayStation's keeps its own`() {
        assertEquals(NetPurpose.XBOX, NetPurpose.of("xbox"))
        assertEquals("xbox", NetPurpose.XBOX.key)
        assertEquals("Xbox sync", NetPurpose.XBOX.label)
        assertEquals(NetPurpose.GAMES, NetPurpose.of("games"))
        assertEquals("PlayStation sync", NetPurpose.GAMES.label)
        // A day with both consoles lists them apart, under their own hosts.
        var day = LedgerDays.add(empty, "m.np.playstation.com", NetPurpose.GAMES, 10, 100)
        day = LedgerDays.add(day, "titlehub.xboxlive.com", NetPurpose.XBOX, 20, 200)
        assertEquals(listOf("games", "xbox"), day.calls.map { it.purpose })
        assertEquals(listOf(NetPurpose.XBOX), LedgerDays.byHost(day).first().purposes)
    }

    @Test
    fun `the game pictures purpose reads back from its key`() {
        assertEquals(NetPurpose.GAME_PICTURES, NetPurpose.of("game_pictures"))
        assertEquals("game_pictures", NetPurpose.GAME_PICTURES.key)
        assertEquals("Game pictures", NetPurpose.GAME_PICTURES.label)
        // Added before Other, which stays last; the keys stay unique.
        assertEquals(NetPurpose.OTHER, NetPurpose.entries.last())
        assertEquals(NetPurpose.entries.size, NetPurpose.entries.map { it.key }.toSet().size)
        // A picture's host lists under its own purpose, apart from PlayStation's sync.
        var day = LedgerDays.add(empty, "image.api.playstation.com", NetPurpose.GAME_PICTURES, 1, 40_000)
        day = LedgerDays.add(day, "m.np.playstation.com", NetPurpose.GAMES, 10, 100)
        assertEquals(listOf(NetPurpose.GAME_PICTURES), LedgerDays.byHost(day).first().purposes)
    }

    @Test
    fun `the feed pictures purpose reads back from its key`() {
        assertEquals(NetPurpose.FEED_PICTURES, NetPurpose.of("feed_pictures"))
        assertEquals("feed_pictures", NetPurpose.FEED_PICTURES.key)
        assertEquals("Feed pictures", NetPurpose.FEED_PICTURES.label)
        // Right after the games' pictures and before Other, which stays last; the keys stay unique.
        assertEquals(NetPurpose.GAME_PICTURES.ordinal + 1, NetPurpose.FEED_PICTURES.ordinal)
        assertEquals(NetPurpose.OTHER, NetPurpose.entries.last())
        assertEquals(NetPurpose.entries.size, NetPurpose.entries.map { it.key }.toSet().size)
        // The feeds' own reads keep their key under the tab's name.
        assertEquals(NetPurpose.WATCH, NetPurpose.of("watch"))
        assertEquals("Feeds", NetPurpose.WATCH.label)
        // A thumbnail's host lists under its own purpose, apart from a feed's read.
        var day = LedgerDays.add(empty, "cdn.example.org", NetPurpose.FEED_PICTURES, 1, 20_000)
        day = LedgerDays.add(day, "news.example", NetPurpose.WATCH, 10, 100)
        assertEquals(listOf(NetPurpose.FEED_PICTURES), LedgerDays.byHost(day).first().purposes)
        assertEquals(listOf("feed_pictures", "watch"), day.calls.map { it.purpose })
    }

    @Test
    fun `bytes read as people say them`() {
        assertEquals("999 B", LedgerDays.bytes(999))
        assertEquals("1 KB", LedgerDays.bytes(1_500))
        assertEquals("2.5 MB", LedgerDays.bytes(2_500_000))
        assertEquals("1.2 GB", LedgerDays.bytes(1_200_000_000))
    }
}
