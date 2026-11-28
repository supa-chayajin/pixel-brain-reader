package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.daily.Shutdown
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The day a game night keeps the shutdown banner away (13.4, the audit's game-night-day-wrong-day):
 * that evening's (D7, D13: hidden until morning), never the next one's.
 */
class GameNightTest {

    /** Work Monday to Friday, 09:00 to 18:00: the banner shows from 17:30 on a work day. */
    private val config = ModesConfig()

    private val thursday = LocalDate.of(2026, 11, 19)
    private val friday = LocalDate.of(2026, 11, 20)
    private val saturday = LocalDate.of(2026, 11, 21)
    private val sunday = LocalDate.of(2026, 11, 22)
    private val monday = LocalDate.of(2026, 11, 23)

    /** True when a game night started at [moment] hides a banner that would show on [day] (at 17:45). */
    private fun hidesAShownBanner(moment: LocalDateTime, day: LocalDate): Boolean =
        GameNight.hidesShutdown(GameNight.dayToHide(config, moment)?.toString(), day) &&
            Shutdown.isDue(config, day.atTime(17, 45), closedDay = null)

    @Test
    fun `a game night after midnight or before work hides no banner`() {
        // Thursday night's session started at 00:20 on Friday, or "Started 30 min ago" tapped at 00:10.
        val afterMidnight = friday.atTime(0, 20)
        val tappedEarlier = friday.atTime(0, 10)
        // A session before work.
        val beforeWork = friday.atTime(7, 0)
        listOf(afterMidnight, tappedEarlier, beforeWork).forEach { moment ->
            // A Perso time outside work hours: the game night itself still guards the session.
            assertTrue(GameNight.guards(ContextMode.PERSONAL, ModeResolver.inWorkHours(config, moment)))
            assertNull("$moment", GameNight.dayToHide(config, moment))
            assertFalse("$moment", hidesAShownBanner(moment, friday))
        }
        // Friday's banner shows that evening, as on any work day.
        assertTrue(Shutdown.isDue(config, friday.atTime(17, 45), closedDay = null))
    }

    @Test
    fun `one from the banner's start hides that day's banner`() {
        listOf(friday.atTime(17, 30), friday.atTime(19, 0), friday.atTime(23, 59)).forEach { moment ->
            assertEquals("$moment", friday, GameNight.dayToHide(config, moment))
            assertTrue("$moment", hidesAShownBanner(moment, friday))
            // Until morning only: the next work day's banner shows.
            assertFalse("$moment", hidesAShownBanner(moment, monday))
        }
        // A minute before the banner starts, the day's banner is still to come.
        assertNull(GameNight.dayToHide(config, friday.atTime(17, 29)))
        // The banner follows the owner's own end of work: 16:00 shows it from 15:30.
        val early = ModesConfig(workEnd = "16:00")
        assertEquals(thursday, GameNight.dayToHide(early, thursday.atTime(15, 30)))
        assertNull(GameNight.dayToHide(early, thursday.atTime(15, 29)))
    }

    @Test
    fun `a weekend evening hides nothing that shows`() {
        val weekend = listOf(
            saturday.atTime(10, 0),
            saturday.atTime(21, 0),
            sunday.atTime(0, 30),
            sunday.atTime(21, 0),
            sunday.atTime(23, 59)
        )
        weekend.forEach { moment ->
            // No banner on a day off; and whatever day is kept, the banners to come all show.
            listOf(saturday, sunday, monday).forEach { day ->
                assertFalse("$moment on $day", hidesAShownBanner(moment, day))
            }
        }
        assertFalse(Shutdown.isDue(config, saturday.atTime(21, 0), closedDay = null))
        assertTrue(Shutdown.isDue(config, monday.atTime(17, 45), closedDay = null))
    }

    @Test
    fun `the banner stays away only on the day kept`() {
        assertTrue(GameNight.hidesShutdown("2026-11-20", friday))
        assertFalse(GameNight.hidesShutdown("2026-11-20", saturday))
        assertFalse(GameNight.hidesShutdown(null, friday))
    }
}
