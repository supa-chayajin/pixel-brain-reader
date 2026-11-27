package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** The games data in an in-memory vault: disk first, corrupt files kept aside, the read-only gate. */
class GamesRepositoryTest {

    private val disk = mutableMapOf<String, String>()
    private val movedAside = mutableListOf<String>()
    private val files: FileRepository = mockk {
        coEvery { readFile(any()) } answers { disk[firstArg()] }
        coEvery { saveFileLocally(any(), any()) } answers { disk[firstArg()] = secondArg() }
        coEvery { fileExists(any()) } answers { disk.containsKey(firstArg()) }
        every { moveAsideCorrupt(any()) } answers {
            val path = firstArg<String>()
            movedAside += path
            disk.remove(path)
            File("aside")
        }
    }
    private val dao: FileContentDao = mockk {
        coEvery { pathsContaining(any()) } answers { disk.filter { it.value.contains(firstArg<String>()) }.keys.toList() }
        coEvery { pathsUnder(any()) } returns emptyList()
        coEvery { contentsUnder(any()) } returns emptyList()
    }
    private val push: VaultPushScheduler = mockk(relaxed = true)
    private val repository = GamesRepository(files, dao, push)

    private val pagePath = "30_Areas/Games/Ring/Ring.md"

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
        disk[pagePath] = "---\ntitle: \"Ring of Ash\"\ndate_updated: '2026-11-01'\ngame_template: souls-like\ngame_state: backlog\n---\n# Ring of Ash\n"
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    private fun record(id: String, date: String, start: String, minutes: Int) =
        GameSessionRecord(id, date, start, minutes, GameSessionRecord.SOURCE_MANUAL, page = pagePath)

    @Test
    fun `pages are found by their key and read from disk`() = runTest {
        assertEquals(listOf("Ring of Ash"), repository.pages().map { it.title })
        assertTrue(repository.setState(pagePath, GameState.PLAYING, LocalDate.of(2026, 11, 14)))
        assertEquals(GameState.PLAYING, repository.page(pagePath)!!.state)
        assertTrue(disk.getValue(pagePath).contains("started: '2026-11-14'"))
        verify { push.requestPush() }
    }

    @Test
    fun `sessions are merged by id into their month, read from disk first`() = runTest {
        disk[GameFiles.sessions("2026-11")] = """{"sessions":[{"id":"desk","date":"2026-11-02","start":"20:00","minutes":30,"source":"manual","page":"$pagePath"}]}"""
        val days = repository.addSessions(listOf(record("a", "2026-11-14", "21:00", 60), record("b", "2026-12-01", "10:00", 15)))
        assertEquals(setOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 12, 1)), days)
        // What another device wrote stays: the month was read before being written.
        assertEquals(listOf("desk", "a"), repository.sessions(java.time.YearMonth.of(2026, 11)).map { it.id })
        repository.addSessions(listOf(record("a", "2026-11-14", "21:00", 75)))
        assertEquals(75, repository.sessions(java.time.YearMonth.of(2026, 11)).single { it.id == "a" }.minutes)
        assertEquals(2, repository.sessionsBetween(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).size)
    }

    @Test
    fun `an unreadable file is moved aside, never overwritten in place`() = runTest {
        disk[GameFiles.LINKS] = "{not json"
        val links = repository.updateLinks { it.link("PPSA1", pagePath) }
        assertEquals(pagePath, links!!.pageOf("PPSA1"))
        assertEquals(listOf(GameFiles.LINKS), movedAside)
        assertEquals(pagePath, repository.links().pageOf("PPSA1"))
    }

    @Test
    fun `a read-only device writes nothing`() = runTest {
        VaultWriteGate.readOnly = true
        assertNull(repository.addSessions(listOf(record("a", "2026-11-14", "21:00", 60))))
        assertFalse(repository.setState(pagePath, GameState.DONE))
        assertNull(repository.updateLinks { it.link("PPSA1", pagePath) })
        assertFalse(disk.keys.any { it.startsWith(GameFiles.FOLDER) })
    }

    @Test
    fun `the day's lines come from the sessions, and a past day's note gets its line within a week`() = runTest {
        repository.addSessions(listOf(record("a", "2026-11-13", "21:00", 155)))
        val lines = repository.linesFor(LocalDate.of(2026, 11, 13))
        assertEquals(LocalTime.of(21, 0), lines.single().time)
        assertEquals("🎮 Ring of Ash · 2 h 35 <!--game:Ring-->", lines.single().text)

        val note = GamesRepository.journalPath(LocalDate.of(2026, 11, 13))
        disk[note] = "# 2026-11-13\n\n## 🗓️ Timeline\n\n- 20:00 Dinner\n\n## 📝 Journal\n\n*No tasks*\n"
        assertTrue(repository.applyPastDay(LocalDate.of(2026, 11, 13), today = LocalDate.of(2026, 11, 14)))
        assertTrue(disk.getValue(note).contains("- 20:00 Dinner\n- 21:00 🎮 Ring of Ash · 2 h 35 <!--game:Ring-->\n"))
        // Today is the burn's, and older than a week is left alone.
        assertFalse(repository.applyPastDay(LocalDate.of(2026, 11, 14), today = LocalDate.of(2026, 11, 14)))
        assertFalse(repository.applyPastDay(LocalDate.of(2026, 11, 1), today = LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun `a fixed session replaces the old one in one write, and a vanished one is refused`() = runTest {
        val old = record("manual:1", "2026-11-15", "21:00", 540)
        val other = record("manual:2", "2026-11-14", "20:00", 60)
        repository.addSessions(listOf(old, other))
        val fixed = old.copy(id = "manual:3", start = "21:30", minutes = 150)
        assertTrue(repository.replaceSession(old, fixed))
        val month = repository.sessions(java.time.YearMonth.of(2026, 11))
        assertEquals(listOf("manual:2", "manual:3"), month.map { it.id })
        assertEquals(150, month.last().minutes)
        assertFalse(repository.replaceSession(old, fixed.copy(minutes = 10)))
        assertEquals(150, repository.sessions(java.time.YearMonth.of(2026, 11)).last().minutes)

        assertTrue(repository.removeSession("manual:3", LocalDate.of(2026, 11, 15)))
        assertEquals(listOf("manual:2"), repository.sessions(java.time.YearMonth.of(2026, 11)).map { it.id })
    }

    @Test
    fun `the console's play time is one frontmatter line, read back by the page`() = runTest {
        assertTrue(repository.setPlayedBefore(pagePath, 228.5, LocalDate.of(2026, 11, 16)))
        assertTrue(disk.getValue(pagePath).contains("\nplayed_before: 228.5\n---\n"))
        assertEquals(228.5, repository.page(pagePath)!!.playedBefore!!, 0.0)
        VaultWriteGate.readOnly = true
        assertFalse(repository.setPlayedBefore(pagePath, 10.0, LocalDate.of(2026, 11, 16)))
    }

    // --- 13.3: maps (ME4) ------------------------------------------------------------------

    private val today = LocalDate.of(2026, 11, 27)
    private val mapPage = "---\ntitle: \"Ring of Ash\"\ndate_updated: '2026-11-01'\ngame_template: souls-like\n" +
        "game_maps:\n  - \"https://ringofash.wiki.example.com/Maps\"\n---\n# Ring of Ash\n"

    @Test
    fun `a map edit reads the page from disk first`() = runTest {
        disk[pagePath] = mapPage
        val seen = repository.page(pagePath)!!
        // Meanwhile the desktop adds a map and a line to the page.
        disk[pagePath] = mapPage
            .replace("game_maps:\n", "game_maps:\n  - \"Desk | https://desk.example.com/Map\"\n")
            .replace("# Ring of Ash\n", "# Ring of Ash\n\nFrom the desktop.\n")
        assertEquals(MapEditResult.SAVED, repository.addMap(seen.path, "https://two.example.org/Maps", "Maps (images)", today))
        val written = disk.getValue(pagePath)
        assertTrue(
            written.contains(
                "game_maps:\n  - \"Desk | https://desk.example.com/Map\"\n  - \"https://ringofash.wiki.example.com/Maps\"\n" +
                    "  - \"Maps (images) | https://two.example.org/Maps\"\n---\n"
            )
        )
        assertTrue(written.contains("From the desktop."))
        assertTrue(written.contains("date_updated: '2026-11-27'"))
        verify { push.requestPush() }

        assertEquals(MapEditResult.SAVED, repository.makeMainMap(pagePath, "https://two.example.org/Maps", today))
        assertEquals("https://two.example.org/Maps", repository.page(pagePath)!!.maps.first().url)
        assertEquals(MapEditResult.SAVED, repository.changeMap(pagePath, "https://two.example.org/Maps", "https://two.example.org/World", "World", today))
        assertEquals(MapEditResult.SAVED, repository.removeMap(pagePath, "https://desk.example.com/Map", today))
        assertEquals(
            listOf("https://two.example.org/World", "https://ringofash.wiki.example.com/Maps"),
            repository.page(pagePath)!!.maps.map { it.url }
        )
        assertEquals("World", repository.page(pagePath)!!.maps.first().given)
        // What the edit refuses, it doesn't write.
        val before = disk.getValue(pagePath)
        assertEquals(MapEditResult.ALREADY_THERE, repository.addMap(pagePath, "https://two.example.org/World", null, today))
        assertEquals(MapEditResult.NOT_PUBLIC_HTTPS, repository.addMap(pagePath, "http://two.example.org/World", null, today))
        assertEquals(before, disk.getValue(pagePath))
    }

    @Test
    fun `map edits refuse on a read-only device and write nothing`() = runTest {
        disk[pagePath] = mapPage
        VaultWriteGate.readOnly = true
        assertEquals(MapEditResult.READ_ONLY, repository.addMap(pagePath, "https://two.example.org/Maps", null, today))
        assertEquals(MapEditResult.READ_ONLY, repository.changeMap(pagePath, "https://ringofash.wiki.example.com/Maps", "https://two.example.org/Maps", null, today))
        assertEquals(MapEditResult.READ_ONLY, repository.removeMap(pagePath, "https://ringofash.wiki.example.com/Maps", today))
        assertEquals(MapEditResult.READ_ONLY, repository.makeMainMap(pagePath, "https://ringofash.wiki.example.com/Maps", today))
        assertEquals(mapPage, disk.getValue(pagePath))
        coVerify(exactly = 0) { files.saveFileLocally(any(), any()) }
    }

    @Test
    fun `a vanished page is gone`() = runTest {
        disk[pagePath] = mapPage
        assertEquals(MapEditResult.GONE, repository.addMap("30_Areas/Games/Gone/Gone.md", "https://two.example.org/Maps", null, today))
        // A map no longer on the page is gone too, and nothing is written.
        assertEquals(MapEditResult.GONE, repository.removeMap(pagePath, "https://not.there.example.com/x", today))
        assertEquals(MapEditResult.GONE, repository.changeMap(pagePath, "https://not.there.example.com/x", "https://two.example.org/Maps", null, today))
        assertEquals(mapPage, disk.getValue(pagePath))
        // A save that fails says so.
        coEvery { files.saveFileLocally(any(), any()) } throws java.io.IOException("disk full")
        assertEquals(MapEditResult.FAILED, repository.addMap(pagePath, "https://two.example.org/Maps", null, today))
        assertEquals(mapPage, disk.getValue(pagePath))
    }

    // --- 13.3: Xbox files and created pages -------------------------------------------------

    @Test
    fun `xbox snapshot and achievement files round-trip, an unreadable one moved aside`() = runTest {
        val snapshot = XboxSnapshot(
            syncedAt = "2026-11-27T22:40:00Z",
            complete = false,
            titles = listOf(
                XboxTitleState(
                    "1915865634", "Ring of Ash", mediaItemType = "Application", achievementSource = 2,
                    minutesPlayed = 1220, lastPlayed = "2026-11-27T21:13:45.441193600Z", currentAchievements = 22
                ),
                XboxTitleState("1234567890", "Brick Wars", visible = false)
            )
        )
        assertTrue(repository.saveXboxSnapshot(snapshot))
        assertEquals(snapshot, repository.xboxSnapshot())
        val raw = disk.getValue(GameFiles.XBOX_SNAPSHOT)
        assertTrue(raw.contains("\"complete\": false"))
        // A default isn't written: an unknown play time stays unknown, never 0.
        assertFalse(raw.contains("\"totalGamerscore\""))
        assertEquals(1, Regex("\"visible\"").findAll(raw).count())

        val file = AchievementSetFile(
            "1915865634", "Ring of Ash", 2,
            listOf(
                AchievementRecord("1", "First Steps", "Finish the prologue.", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
                AchievementRecord("2", "The Hidden Boss", gamerscore = 50, secret = true)
            )
        )
        assertTrue(repository.saveAchievementSet(file))
        assertEquals(file, repository.achievementSet("1915865634"))
        assertTrue(disk.containsKey("10_Journal/data/games/achievements/1915865634.json"))
        assertNull(repository.achievementSet("999"))
        assertEquals(1, file.earnedCount)
        assertEquals(10, file.earnedGamerscore)
        assertEquals(60, file.totalGamerscore)

        // An unreadable snapshot is moved aside before the next one is written.
        disk[GameFiles.XBOX_SNAPSHOT] = "{not json"
        assertNull(repository.xboxSnapshot())
        assertTrue(repository.saveXboxSnapshot(snapshot))
        assertEquals(listOf(GameFiles.XBOX_SNAPSHOT), movedAside)
        assertEquals(snapshot, repository.xboxSnapshot())
    }

    @Test
    fun `pages are created, never over a file, and nothing on a read-only device`() = runTest {
        val plan = PagePlan("30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), listOf("1915865634"), GameConsole.XBOX)
        val page = GamePageCreation.render(plan, null, null, today)
        val overwrite = NewGamePage(plan.copy(path = pagePath, title = "Ring of Ash"), "---\ngame_template: other\n---\n# Overwritten\n")
        val created = repository.createPages(listOf(page, overwrite))
        assertEquals(listOf(plan.path), created.map { it.path })
        assertTrue(created.single().exactName)
        assertEquals(page.content, disk.getValue(plan.path))
        assertFalse(disk.getValue(pagePath).contains("Overwritten"))
        verify { push.requestPush() }
        assertTrue(repository.createPages(emptyList()).isEmpty())

        VaultWriteGate.readOnly = true
        val other = NewGamePage(plan.copy(path = "30_Areas/Games/Other/Other.md"), page.content)
        assertTrue(repository.createPages(listOf(other)).isEmpty())
        assertFalse(disk.containsKey("30_Areas/Games/Other/Other.md"))
    }

    @Test
    fun `the Other template and the notes under a folder are read for page creation`() = runTest {
        assertNull(repository.otherTemplate())
        disk[GamePageCreation.TEMPLATE_PATH] = "---\ngame_template: other\n---\n# {{title}}\n"
        assertEquals("---\ngame_template: other\n---\n# {{title}}\n", repository.otherTemplate())
        coEvery { dao.pathsUnder("30_Areas/Games/") } returns listOf("30_Areas/Games/Ring/Ring.md")
        coEvery { dao.pathsUnder("") } returns listOf("Ring/Ring.md")
        assertEquals(listOf("30_Areas/Games/Ring/Ring.md"), repository.notePathsUnder("30_Areas/Games"))
        assertEquals(listOf("Ring/Ring.md"), repository.notePathsUnder(""))
        coEvery { dao.pathsUnder("Broken/") } throws IllegalStateException("database closed")
        assertTrue(repository.notePathsUnder("Broken").isEmpty())
    }

    @Test
    fun `the resolver finds Xbox sessions through Xbox links only`() = runTest {
        val ring = repository.page(pagePath)!!
        val links = GameLinks().linkConsole(GameConsole.XBOX, "1915865634", pagePath)
        val resolve = repository.resolver(listOf(ring), links)
        assertEquals(ring, resolve(GameSessionRecord("x", "2026-11-27", "20:00", 30, GameSessionRecord.SOURCE_XBOX, titleId = "1915865634")))
        assertNull(resolve(GameSessionRecord("p", "2026-11-27", "20:00", 30, GameSessionRecord.SOURCE_PSN, titleId = "1915865634")))
        assertEquals(ring, resolve(record("m", "2026-11-27", "20:00", 30)))
        // The day's line adds both consoles up.
        repository.updateLinks { it.link("PPSA00001_00", pagePath).linkConsole(GameConsole.XBOX, "1915865634", pagePath) }
        repository.addSessions(
            listOf(
                GameSessionRecord("psn:PPSA00001_00:a", "2026-11-27", "20:00", 60, GameSessionRecord.SOURCE_PSN, titleId = "PPSA00001_00"),
                GameSessionRecord("xbox:1915865634:a", "2026-11-27", "22:00", 45, GameSessionRecord.SOURCE_XBOX, titleId = "1915865634")
            )
        )
        val line = repository.dayLines(LocalDate.of(2026, 11, 27)).single()
        assertEquals(LocalTime.of(20, 0), line.start)
        assertEquals(105, line.minutes)
    }
}

