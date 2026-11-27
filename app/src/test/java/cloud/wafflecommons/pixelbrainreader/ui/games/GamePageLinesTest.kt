package cloud.wafflecommons.pixelbrainreader.ui.games

import cloud.wafflecommons.pixelbrainreader.data.games.AchievementRecord
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The game page's achievement lines (13.3, XB2): earned first by date, then the others, by id. */
class GamePageLinesTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun file(titleId: String, title: String, vararg achievements: AchievementRecord) =
        AchievementSetFile(titleId = titleId, title = title, achievements = achievements.toList())

    @Test
    fun `achievement lines put the earned ones first by date, then the others by id`() {
        val ringOfAsh = file(
            "1915865634", "Ring of Ash",
            AchievementRecord("10", "Late Bloomer", gamerscore = 20),
            AchievementRecord("2", "The Hidden Boss", gamerscore = 50, secret = true),
            AchievementRecord("1", "First Steps", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
            AchievementRecord("4", "Same Night", gamerscore = 10, earnedAt = "2026-11-01T21:00:00Z"),
            AchievementRecord("3", "Lord of Ash", gamerscore = 30, earnedAt = "2026-11-01T20:00:00Z"),
            AchievementRecord("A", "Lettered", gamerscore = 5)
        )

        val lines = achievementLines(listOf(ringOfAsh), paris)

        // Earned: the 1st (3 before 4, by number), then the 2nd; then the others, 2 before 10 (not as text), then a non-numeric id.
        assertEquals(listOf("3", "4", "1", "2", "10", "A"), lines.map { it.record.id })
        assertEquals(LocalDate.of(2026, 11, 1), lines[0].earnedOn)
        assertEquals(LocalDate.of(2026, 11, 2), lines[2].earnedOn)
        assertTrue(lines.drop(3).all { it.earnedOn == null })
        assertTrue(lines.all { it.titleId == "1915865634" && it.setTitle == "Ring of Ash" })
    }

    @Test
    fun `two titles on one page keep each line's title`() {
        val one = file("1915865634", "Ring of Ash", AchievementRecord("1", "First Steps", earnedAt = "2026-11-03T20:00:00Z"))
        val legacy = file("1234567890", "Ring of Ash (360)", AchievementRecord("1", "Old Steps", earnedAt = "2026-11-02T20:00:00Z"))

        val lines = achievementLines(listOf(one, legacy), paris)

        assertEquals(listOf("Ring of Ash (360)", "Ring of Ash"), lines.map { it.setTitle })
        assertEquals(listOf("1234567890", "1915865634"), lines.map { it.titleId })
    }

    @Test
    fun `earned dates read in the zone`() {
        // 23:30 UTC on 1 November is 00:30 on 2 November in Paris (UTC+1 in winter).
        val late = file("1915865634", "Ring of Ash", AchievementRecord("7", "Midnight Run", earnedAt = "2026-11-01T23:30:00Z"))

        assertEquals(LocalDate.of(2026, 11, 2), achievementLines(listOf(late), paris).single().earnedOn)
        assertEquals(LocalDate.of(2026, 11, 1), achievementLines(listOf(late), ZoneId.of("UTC")).single().earnedOn)
    }

    @Test
    fun `an earned date that doesn't read counts as not earned`() {
        val odd = file("1915865634", "Ring of Ash", AchievementRecord("5", "Odd Date", earnedAt = "yesterday"))

        assertNull(achievementLines(listOf(odd), paris).single().earnedOn)
    }

    @Test
    fun `no file, no line`() {
        assertTrue(achievementLines(emptyList(), paris).isEmpty())
        assertTrue(achievementLines(listOf(file("1915865634", "Ring of Ash")), paris).isEmpty())
    }
}
