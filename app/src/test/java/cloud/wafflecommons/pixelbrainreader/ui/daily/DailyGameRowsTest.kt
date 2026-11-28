package cloud.wafflecommons.pixelbrainreader.ui.daily

import cloud.wafflecommons.pixelbrainreader.data.games.ArtSource
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameDayLine
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Daily's game rows (13.4): each game line with its picture, and only the page's own day's (the
 * audit's daily-game-rows-other-day: the day before's line flashed on another page while the new
 * day's lines were read).
 */
class DailyGameRowsTest {

    private val glassTower = page("30_Games/Glass_Tower.md", "Glass Tower")
    private val tinOrchard = page("30_Games/Tin_Orchard.md", "Tin Orchard")
    private val friday = LocalDate.of(2026, 11, 27)
    private val saturday = friday.plusDays(1)

    private fun page(path: String, title: String) =
        GamePage(path = path, title = title, aliases = emptyList(), template = GameTemplate.OTHER, state = GameState.PLAYING, started = null, finished = null)

    @Test
    fun `the rows of another day are left out`() {
        val fridayRows = dailyGameRows(friday, listOf(GameDayLine(glassTower, LocalTime.of(21, 0), 120)), emptyMap())
        val saturdayRows = dailyGameRows(saturday, listOf(GameDayLine(tinOrchard, LocalTime.of(10, 30), 45)), emptyMap())

        // Swiped to Saturday while Friday's rows are still held: none of them shows there.
        assertEquals(emptyList<DailyGameRow>(), gameRowsOn(fridayRows, saturday))
        assertEquals(fridayRows, gameRowsOn(fridayRows, friday))
        assertEquals(saturdayRows, gameRowsOn(fridayRows + saturdayRows, saturday))
    }

    @Test
    fun `each row is the game's line with its picture, the initials without one`() {
        val art = GameArtRules.initialsOnly("Glass Tower")
            .copy(consoleUrl = "https://image.api.playstation.com/cdn/glass-tower.png", source = ArtSource.PSN)
        val rows = dailyGameRows(
            friday,
            listOf(GameDayLine(glassTower, LocalTime.of(21, 0), 155), GameDayLine(tinOrchard, LocalTime.of(22, 30), 30)),
            mapOf(glassTower.path to art)
        )

        val first = rows[0].entry
        assertEquals("🎮 Glass Tower · 2 h 35", first.content)
        assertEquals(friday, first.date)
        assertEquals(LocalTime.of(21, 0), first.time)
        // A tap finds the page again from the row; the row is never a board entry.
        assertTrue(DailyGamesViewModel.isGameRow(first))
        assertEquals(glassTower.path, DailyGamesViewModel.pageOf(first))
        assertEquals(art, rows[0].art)
        // No picture read for this one: its initials, in its own shape and tone.
        assertEquals("TO", rows[1].art.initials)
        assertNull(rows[1].art.consoleUrl)
        assertNull(rows[1].art.ownFile)
    }
}
