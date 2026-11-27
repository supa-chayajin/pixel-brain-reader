package cloud.wafflecommons.pixelbrainreader.ui.games

import cloud.wafflecommons.pixelbrainreader.data.games.ConsoleLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Games tab's library with both consoles (13.3, XB-E): rows, hours, achievements and the folds (pure). */
class GamesLibraryTest {

    private val zone = ZoneId.of("Europe/Paris")

    private val ring = GamePage(
        "30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null
    )
    private val bricks = GamePage(
        "30_Areas/Games/Brick_Wars/Brick_Wars.md", "Brick Wars", emptyList(), GameTemplate.LEGO, GameState.BACKLOG, null, null,
        playedBefore = 3.0
    )

    private fun psn(
        id: String,
        name: String,
        seconds: Long,
        lastPlayed: String? = null,
        progress: Int? = null,
        imageUrl: String? = null
    ) = PsnTitleState(titleId = id, name = name, playSeconds = seconds, lastPlayed = lastPlayed, progress = progress, imageUrl = imageUrl)

    private fun xbox(
        id: String,
        name: String,
        minutes: Long?,
        lastPlayed: String? = null,
        earned: Int? = null,
        total: Int? = null,
        progress: Int? = null,
        imageUrl: String? = null
    ) = XboxTitleState(
        titleId = id,
        name = name,
        minutesPlayed = minutes,
        lastPlayed = lastPlayed,
        currentAchievements = earned,
        totalAchievements = total,
        progress = progress,
        imageUrl = imageUrl
    )

    private fun row(page: GamePage, psn: List<PsnTitleState> = emptyList(), xbox: List<XboxTitleState> = emptyList(), sessionMinutes: Int = 0) =
        GamesLibrary.row(page, psn, xbox, sessionMinutes, playedDays = emptyList(), zone = zone)

    // --- Rows ---------------------------------------------------------------------------------------

    @Test
    fun `hours add both consoles`() {
        // 2 h on PlayStation, 90 min on Xbox: the measured totals add up, the sessions don't count on top.
        val measured = row(ring, psn = listOf(psn("PPSA00001_00", "Ring of Ash", 7_200)), xbox = listOf(xbox("1915865634", "Ring of Ash", 90)), sessionMinutes = 45)
        assertEquals(3.5, measured.hours!!, 1e-9)

        // An Xbox title alone measures too.
        assertEquals(1.5, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 90))).hours!!, 1e-9)

        // Neither console measures: the typed hours plus the sessions (XB3).
        val typed = row(bricks, xbox = listOf(xbox("1234567890", "Brick Wars", null)), sessionMinutes = 60)
        assertEquals(4.0, typed.hours!!, 1e-9)

        // Nothing measured nor typed: no hours.
        assertNull(row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", null))).hours)
    }

    @Test
    fun `the achievement percent comes from the file's total, else titlehub's progress`() {
        // The snapshot's total is the achievements file's: 22 of 50.
        assertEquals(44, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = 50, progress = 10))).achievementPercent)
        // No file read yet (titlehub's own total reads 0 for an Xbox One title): titlehub's progress.
        assertEquals(34, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = null, progress = 34))).achievementPercent)
        assertEquals(34, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = 0, progress = 34))).achievementPercent)
        // A page holding two Xbox titles (a 360 version beside the One one) shows the best.
        val two = row(
            ring,
            xbox = listOf(
                xbox("1915865634", "Ring of Ash", 100, earned = 5, total = 50),
                xbox("1234567890", "Ring of Ash (360)", 10, earned = 10, total = 20)
            )
        )
        assertEquals(50, two.achievementPercent)
        // Without an Xbox title: no achievements, PlayStation's trophies as before.
        val psnOnly = row(ring, psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, progress = 80)))
        assertNull(psnOnly.achievementPercent)
        assertEquals(80, psnOnly.trophyPercent)
        // An Xbox title without any figure: none.
        assertNull(row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100))).achievementPercent)
    }

    @Test
    fun `the icon is PlayStation's, else Xbox's`() {
        val both = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, imageUrl = "https://psn.example.com/ring.png")),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, imageUrl = "https://xbox.example.com/ring.png"))
        )
        assertEquals("https://psn.example.com/ring.png", both.iconUrl)

        val xboxOnly = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600)),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, imageUrl = "https://xbox.example.com/ring.png"))
        )
        assertEquals("https://xbox.example.com/ring.png", xboxOnly.iconUrl)

        assertNull(row(ring).iconUrl)
    }

    @Test
    fun `last played is the latest of both consoles and the sessions`() {
        val consoles = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, lastPlayed = "2026-11-20T20:00:00Z")),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, lastPlayed = "2026-11-26T23:30:00Z"))
        )
        // 23:30 UTC is past midnight in Paris: the 27th.
        assertEquals(LocalDate.of(2026, 11, 27), consoles.lastPlayed)

        val withSessions = GamesLibrary.row(
            ring,
            psn = emptyList(),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, lastPlayed = "2026-11-20T20:00:00Z")),
            sessionMinutes = 30,
            playedDays = listOf(LocalDate.of(2026, 11, 22)),
            zone = zone
        )
        assertEquals(LocalDate.of(2026, 11, 22), withSessions.lastPlayed)
    }

    // --- The folds -----------------------------------------------------------------------------------

    @Test
    fun `unlinked titles include dangling links, per console, hidden ones left out`() {
        val psnSnapshot = PsnSnapshot(
            syncedAt = "2026-11-27T22:40:00Z",
            titles = listOf(
                psn("PPSA00001_00", "Ring of Ash", 3_600, "2026-11-20T20:00:00Z"), // linked to a page: in the library
                psn("PPSA00002_00", "Brick Wars", 1_800, "2026-11-21T20:00:00Z"), // linked to a deleted page: back in the fold
                psn("CUSA00002_00", "Outlaw Trail 2", 7_200, "2026-11-22T20:00:00Z"), // hidden on PlayStation
                psn("PPSA00003_00", "Never Played", 0) // no play time: left out
            )
        )
        val xboxSnapshot = XboxSnapshot(
            syncedAt = "2026-11-27T22:40:00Z",
            titles = listOf(
                xbox("1915865634", "Ring of Ash", 120, "2026-11-23T20:00:00Z"), // a PSN key with this id doesn't claim it
                xbox("1234567890", "Brick Wars", 60, "2026-11-24T20:00:00Z"), // hidden on Xbox
                xbox("1111111111", "Outlaw Trail 2", null, "2026-11-25T20:00:00Z"), // PlayStation's hide stays PlayStation's
                xbox("2222222222", "Ring of Ash II", 30, "2026-11-26T20:00:00Z"), // linked to a page: in the library
                xbox("3333333333", "Brick Wars Classic", 5, "2026-11-19T20:00:00Z") // linked to a deleted page
            )
        )
        val links = GameLinks(
            links = mapOf(
                "PPSA00001_00" to ring.path,
                "PPSA00002_00" to "30_Areas/Games/Gone/Gone.md",
                "1915865634" to ring.path
            ),
            hidden = listOf("CUSA00002_00", "1111111111"),
            xbox = ConsoleLinks(
                links = mapOf("2222222222" to ring.path, "3333333333" to "30_Areas/Games/Gone/Gone.md"),
                hidden = listOf("1234567890", "PPSA00002_00")
            )
        )

        val titles = GamesLibrary.unlinked(psnSnapshot, xboxSnapshot, links, setOf(ring.path, bricks.path), zone)

        assertEquals(
            listOf(
                "1111111111" to GameConsole.XBOX,
                "1915865634" to GameConsole.XBOX,
                "PPSA00002_00" to GameConsole.PSN,
                "3333333333" to GameConsole.XBOX
            ),
            titles.map { it.titleId to it.console }
        )
        assertEquals(0.5, titles.first { it.titleId == "PPSA00002_00" }.hours!!, 1e-9)
        assertEquals(2.0, titles.first { it.titleId == "1915865634" }.hours!!, 1e-9)
    }

    @Test
    fun `Xbox titles with unknown minutes have no hours`() {
        val titles = GamesLibrary.unlinked(
            psn = null,
            xbox = XboxSnapshot(titles = listOf(xbox("1915865634", "Ring of Ash", null), xbox("1234567890", "Brick Wars", 90))),
            links = GameLinks(),
            pagePaths = emptySet(),
            zone = zone
        )

        assertNull(titles.first { it.titleId == "1915865634" }.hours)
        assertEquals(1.5, titles.first { it.titleId == "1234567890" }.hours!!, 1e-9)
        assertEquals(setOf(GameConsole.XBOX), titles.map { it.console }.toSet())
    }

    @Test
    fun `newest first`() {
        val titles = GamesLibrary.unlinked(
            psn = PsnSnapshot(
                titles = listOf(
                    psn("PPSA00001_00", "Ring of Ash", 60, "2026-11-20T20:00:00Z"),
                    psn("PPSA00002_00", "Brick Wars", 60, null)
                )
            ),
            xbox = XboxSnapshot(
                titles = listOf(
                    xbox("1234567890", "Brick Wars", 10, "2026-11-20T21:00:00Z"),
                    xbox("1915865634", "Outlaw Trail 2", 10, "2026-11-26T23:30:00.4411936Z")
                )
            ),
            links = GameLinks(),
            pagePaths = emptySet(),
            zone = zone
        )

        // Both consoles in one order, the latest played first; a title without a time last.
        assertEquals(listOf("1915865634", "1234567890", "PPSA00001_00", "PPSA00002_00"), titles.map { it.titleId })
        // The day is the zone's: 23:30 UTC on the 26th is the 27th in Paris.
        assertEquals(LocalDate.of(2026, 11, 27), titles.first().lastPlayed)
        assertNull(titles.last().lastPlayed)
    }
}
