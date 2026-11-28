package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.AchievementRecord
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSection
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** A title's achievements file, merged at each sync (XB2), and when it needs reading again. */
class XboxAchievementsTest {

    private val now = Instant.parse("2026-11-27T22:40:00Z")
    private val ring = "1915865634"

    private fun fetched(
        id: String,
        earned: Boolean,
        unlockedAt: String? = null,
        name: String = "Achievement $id",
        gamerscore: Int = 10,
        secret: Boolean = false
    ) = FetchedAchievement(id, name, "Do the thing.", gamerscore, secret, earned, unlockedAt?.let(Instant::parse))

    @Test
    fun `an earned date is kept from the file`() {
        val previous = AchievementSetFile(
            ring, "Ring of Ash", 2,
            listOf(AchievementRecord("1", "First Steps", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"))
        )

        // Xbox reports another time now: the file's date stays.
        val file = XboxAchievements.merge(ring, "Ring of Ash", 2, listOf(fetched("1", true, "2026-11-20T10:00:00Z", "First Steps")), previous, now)
        assertEquals("2026-11-02T20:10:00Z", file.achievements.single().earnedAt)

        // Seen earned for the first time: Xbox's own date.
        val fresh = XboxAchievements.merge(ring, "Ring of Ash", 2, listOf(fetched("2", true, "2026-11-20T10:00:00Z")), previous, now)
        assertEquals("2026-11-20T10:00:00Z", fresh.achievements.single().earnedAt)
    }

    @Test
    fun `an undated unlock is dated the first time it is seen`() {
        // A 360 achievement unlocked offline comes without a real date.
        val first = XboxAchievements.merge("1480000001", "Outlaw Trail II", 1, listOf(fetched("7", earned = true)), null, now)
        assertEquals(now.toString(), first.achievements.single().earnedAt)
        assertEquals(1, first.sourceVersion)

        // The next sync keeps that first date.
        val later = XboxAchievements.merge("1480000001", "Outlaw Trail II", 1, listOf(fetched("7", earned = true)), first, now.plusSeconds(86_400))
        assertEquals(now.toString(), later.achievements.single().earnedAt)
    }

    @Test
    fun `unearned names stay in the file only`() {
        val file = XboxAchievements.merge(
            ring, "Ring of Ash", 2,
            listOf(
                fetched("1", true, "2026-11-02T20:10:00Z", "First Steps"),
                fetched("2", false, name = "The Hidden Boss", gamerscore = 50, secret = true)
            ),
            null, now
        )

        val hidden = file.achievements.single { it.id == "2" }
        assertEquals("The Hidden Boss", hidden.name)
        assertNull(hidden.earnedAt)
        assertTrue(hidden.secret)
        assertEquals(1, file.earnedCount)
        assertEquals(60, file.totalGamerscore)
        assertEquals(10, file.earnedGamerscore)
        // The page's section lists the earned one only.
        val lines = AchievementSection.lines(listOf(file), ZoneId.of("Europe/Paris"))
        assertFalse(lines.any { it.contains("The Hidden Boss") })
        assertTrue(lines.any { it.contains("First Steps") })
    }

    @Test
    fun `achievements keep their ids' order, as numbers`() {
        val file = XboxAchievements.merge(ring, "Ring of Ash", 2, listOf(fetched("10", false), fetched("2", false), fetched("1", false), fetched("1", false)), null, now)
        assertEquals(listOf("1", "2", "10"), file.achievements.map { it.id })
    }

    @Test
    fun `moved when the file is missing or counts other earned achievements or gamerscore`() {
        val title = XboxTitleState(ring, "Ring of Ash", achievementSource = 2, currentAchievements = 1, currentGamerscore = 10)
        assertTrue(XboxAchievements.moved(title, null))

        val file = AchievementSetFile(
            ring,
            achievements = listOf(
                AchievementRecord("1", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
                AchievementRecord("2", gamerscore = 50)
            )
        )
        assertFalse(XboxAchievements.moved(title, file))
        assertTrue(XboxAchievements.moved(title.copy(currentAchievements = 2, currentGamerscore = 60), file))
        // The same count, another gamerscore.
        assertTrue(XboxAchievements.moved(title.copy(currentGamerscore = 50), file))
        // titlehub without its summary: only a missing file moves.
        assertFalse(XboxAchievements.moved(title.copy(currentAchievements = null, currentGamerscore = null), file))
    }

    @Test
    fun `a title without achievements never moves`() {
        val title = XboxTitleState("1234567890", "Brick Wars", achievementSource = 0, currentAchievements = 3)
        assertFalse(XboxAchievements.moved(title, null))
        assertFalse(XboxAchievements.moved(title, AchievementSetFile("1234567890")))
    }

    @Test
    fun `names lose invisible characters in the file`() {
        val file = XboxAchievements.merge(
            ring, "Glass\u202E Tower\u200B", 2,
            listOf(
                fetched("1", true, "2026-11-02T20:10:00Z", name = "Top\u200B Floor\u202E"),
                FetchedAchievement("2", "Line\nbreak", "Desc\u2066ription\u2069 here", 5, secret = false, earned = false, unlockedAt = null)
            ),
            null, now
        )
        assertEquals("Glass Tower", file.title)
        assertEquals("Top Floor", file.achievements[0].name)
        assertEquals("Do the thing.", file.achievements[0].description)
        assertEquals("Line break", file.achievements[1].name)
        assertEquals("Description here", file.achievements[1].description)
    }
}
