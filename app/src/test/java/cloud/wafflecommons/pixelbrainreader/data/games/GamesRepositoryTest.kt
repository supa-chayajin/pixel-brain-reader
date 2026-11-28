package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.AssetRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** The games data in an in-memory vault: disk first, corrupt files kept aside, the read-only gate. */
class GamesRepositoryTest {

    private val disk = mutableMapOf<String, String>()
    private val movedAside = mutableListOf<String>()

    /** A real folder for what the repository lists (the sessions folder, a path's case): never the vault. */
    private val root: File = Files.createTempDirectory("games-repository").toFile()
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
        every { getLocalFile(any()) } answers { File(root, firstArg<String>()) }
    }
    private val dao: FileContentDao = mockk {
        coEvery { pathsContaining(any()) } answers { disk.filter { it.value.contains(firstArg<String>()) }.keys.toList() }
        coEvery { pathsUnder(any()) } returns emptyList()
        coEvery { contentsUnder(any()) } returns emptyList()
    }
    private val push: VaultPushScheduler = mockk(relaxed = true)

    /** The vault's pictures (13.4): what the attachment index lists, and their bytes. */
    private val attachments = mutableListOf<String>()
    private val pictures = mutableMapOf<String, ByteArray>()
    private val discovery: VaultDiscoveryRepository = mockk {
        every { observeAttachmentIndex() } answers { flowOf(VaultFileIndex(attachments.toList())) }
    }
    private val assets: AssetRepository = mockk {
        coEvery { writeAt(any(), any()) } answers {
            if (VaultWriteGate.readOnly) {
                false
            } else {
                pictures[firstArg()] = secondArg()
                if (firstArg<String>() !in attachments) attachments += firstArg<String>()
                true
            }
        }
        coEvery { deleteAt(any()) } answers { if (VaultWriteGate.readOnly) false else pictures.remove(firstArg<String>()) != null }
        every { safeFile(any()) } answers { firstArg<String>().takeIf { it in pictures }?.let { File(root, it) } }
    }
    private val repository = GamesRepository(files, dao, push, GitSyncCoordinator(), discovery, assets)

    private val pagePath = "30_Areas/Games/Ring/Ring.md"
    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
        disk[pagePath] = "---\ntitle: \"Ring of Ash\"\ndate_updated: '2026-11-01'\ngame_template: souls-like\ngame_state: backlog\n---\n# Ring of Ash\n"
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        root.deleteRecursively()
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
        disk[GameFiles.sessions("2026-11")] = "{not json"
        assertEquals(setOf(LocalDate.of(2026, 11, 14)), repository.addSessions(listOf(record("a", "2026-11-14", "21:00", 60))))
        assertEquals(listOf(GameFiles.sessions("2026-11")), movedAside)
        assertEquals(listOf("a"), repository.sessions(YearMonth.of(2026, 11)).map { it.id })
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
    fun `a relinked page's old line goes when its day is edited again`() = runTest {
        // 13.4 review: the sessions of a page that's gone, linked to Ring since; the old line names no page.
        repository.addSessions(listOf(record("a", "2026-11-13", "21:00", 60)))
        val note = GamesRepository.journalPath(LocalDate.of(2026, 11, 13))
        disk[note] = "# 2026-11-13\n\n## 🗓️ Timeline\n\n- 21:00 🎮 Old Name · 1 h <!--game:Old_Name-->\n\n## 📝 Journal\n\n*No tasks*\n"
        assertTrue(repository.applyPastDay(LocalDate.of(2026, 11, 13), today = LocalDate.of(2026, 11, 14), replacingKeys = setOf("Old_Name")))
        val content = disk.getValue(note)
        assertFalse(content.contains("<!--game:Old_Name-->"))
        assertEquals(1, content.lines().count { it.contains("<!--game:Ring-->") })
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

    // --- 13.4: links.json is never rebuilt, a failed read writes nothing (GA3) ----------------------

    @Test
    fun `an unreadable links json is refused and never rebuilt`() = runTest {
        disk[GameFiles.LINKS] = "{not json"
        assertNull(repository.updateLinks { it.link("PPSA1", pagePath) })
        assertEquals("{not json", disk[GameFiles.LINKS])
        assertTrue(movedAside.isEmpty())
        val read = repository.linksRead()
        assertFalse(read.readable)
        assertEquals(GameLinks(), read.links)
        assertEquals(GameLinks(), repository.links())
        // Missing or blank, it is readable: empty links, and the first change writes it.
        disk[GameFiles.LINKS] = "  \n"
        assertTrue(repository.linksRead().readable)
        disk.remove(GameFiles.LINKS)
        assertTrue(repository.linksRead().readable)
        assertEquals(pagePath, repository.updateLinks { it.link("PPSA1", pagePath) }!!.pageOf("PPSA1"))
        assertEquals(pagePath, repository.links().pageOf("PPSA1"))
    }

    @Test
    fun `a failed read writes nothing and moves nothing aside`() = runTest {
        disk[GameFiles.sessions("2026-11")] =
            """{"sessions":[{"id":"desk","date":"2026-11-02","start":"20:00","minutes":30,"source":"manual","page":"$pagePath"}]}"""
        disk[GameFiles.LINKS] = """{"links":{"PPSA1":"$pagePath"}}"""
        coEvery { files.readFile(GameFiles.sessions("2026-11")) } throws java.io.IOException("I/O error")
        coEvery { files.readFile(GameFiles.LINKS) } throws java.io.IOException("I/O error")
        val before = disk.toMap()
        assertNull(repository.addSessions(listOf(record("a", "2026-11-14", "21:00", 60))))
        assertNull(repository.updateLinks { it.link("PPSA2", pagePath) })
        assertFalse(repository.removeSession("desk", LocalDate.of(2026, 11, 2)))
        assertFalse(repository.linksRead().readable)
        assertEquals(before, disk)
        assertTrue(movedAside.isEmpty())
        coVerify(exactly = 0) { files.saveFileLocally(any(), any()) }
    }

    // --- 13.4: sessions (GA1, GA3) -------------------------------------------------------------

    @Test
    fun `two games' typed sessions at the same minute are both kept`() = runTest {
        val other = "30_Areas/Games/Tin_Orchard/Tin_Orchard.md"
        val day = LocalDate.of(2026, 11, 14)
        val a = ManualSessions.record(pagePath, day, LocalTime.of(21, 0), 60, zone)!!
        val b = ManualSessions.record(other, day, LocalTime.of(21, 0), 45, zone)!!
        repository.addSessions(listOf(a))
        repository.addSessions(listOf(b))
        assertEquals(setOf(a, b), repository.sessions(YearMonth.of(2026, 11)).toSet())
        // Typing the first game's session again replaces it, and only it.
        val retyped = a.copy(minutes = 75)
        repository.addSessions(listOf(retyped))
        assertEquals(setOf(retyped, b), repository.sessions(YearMonth.of(2026, 11)).toSet())
        // Fixing another of B's sessions onto A's minute keeps A's.
        val early = ManualSessions.record(other, day, LocalTime.of(19, 0), 30, zone)!!
        repository.addSessions(listOf(early))
        val fixed = ManualSessions.edited(early, LocalTime.of(21, 0), 40, zone)!!
        assertTrue(repository.replaceSession(early, fixed))
        assertEquals(setOf(retyped, fixed), repository.sessions(YearMonth.of(2026, 11)).toSet())
    }

    @Test
    fun `a replay that moved month leaves no copy and returns the old date`() = runTest {
        val first = GameSessionRecord("psn:PPSA1:2026-11-29T22:40:00Z", "2026-11-30", "21:00", 120, GameSessionRecord.SOURCE_PSN, titleId = "PPSA1")
        assertEquals(setOf(LocalDate.of(2026, 11, 30)), repository.addSessions(listOf(first)))
        // The snapshot wasn't saved: the next run replays from the same baseline, Dec 1's play in it.
        val replay = first.copy(date = "2026-12-01", start = "20:00", minutes = 180)
        assertEquals(setOf(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30)), repository.addSessions(listOf(replay)))
        assertTrue(repository.sessions(YearMonth.of(2026, 11)).isEmpty())
        assertEquals(listOf(replay), repository.sessions(YearMonth.of(2026, 12)))
        // Within a month it is replaced, and the day it left is returned too.
        val again = replay.copy(date = "2026-12-02", minutes = 200)
        assertEquals(setOf(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 1)), repository.addSessions(listOf(again)))
        assertEquals(listOf(again), repository.sessions(YearMonth.of(2026, 12)))
    }

    @Test
    fun `all sessions read every month on disk`() = runTest {
        val folder = File(root, "${GameFiles.FOLDER}/sessions").apply { mkdirs() }
        listOf("2025-03.json", "2026-11.json", "2026-11.json.corrupt-1", "notes.txt").forEach { File(folder, it).writeText("x") }
        File(folder, "2026-12.json").mkdirs()
        disk[GameFiles.sessions("2025-03")] =
            """{"sessions":[{"id":"old","date":"2025-03-02","start":"20:00","minutes":30,"source":"manual","page":"$pagePath"}]}"""
        disk[GameFiles.sessions("2026-11")] =
            """{"sessions":[{"id":"new","date":"2026-11-02","start":"20:00","minutes":45,"source":"manual","page":"$pagePath"}]}"""
        assertEquals(listOf(YearMonth.of(2025, 3), YearMonth.of(2026, 11)), repository.sessionMonths())
        assertEquals(listOf("old", "new"), repository.allSessions().map { it.id })
        // More than a year back still counts in a game's typed hours.
        val resolve = repository.resolver(repository.pages(), GameLinks())
        assertEquals(75, GameDays.totals(repository.allSessions(), resolve)[pagePath])
        // No sessions folder: none.
        folder.deleteRecursively()
        assertTrue(repository.allSessions().isEmpty())
    }

    @Test
    fun `a moved page's typed sessions still resolve`() = runTest {
        val movedPath = "30_Areas/Done/Ring/Ring.md"
        disk[movedPath] = disk.remove(pagePath)!!
        val moved = repository.page(movedPath)!!
        val session = record("a", "2026-11-14", "21:00", 60)
        assertEquals(moved, repository.resolver(listOf(moved), GameLinks())(session))
        // Two pages with that file name: no guess.
        val twin = moved.copy(path = "30_Areas/Other/Ring/Ring.md")
        assertNull(repository.resolver(listOf(moved, twin), GameLinks())(session))
        // A console session follows its link only.
        val psn = GameSessionRecord("p", "2026-11-14", "20:00", 30, GameSessionRecord.SOURCE_PSN, titleId = "PPSA1")
        assertNull(repository.resolver(listOf(moved), GameLinks(links = mapOf("PPSA1" to pagePath)))(psn))
    }

    @Test
    fun `a past day isn't edited while the mirror is empty, links can't be read, or nothing resolves`() = runTest {
        repository.addSessions(listOf(record("a", "2026-11-13", "21:00", 155)))
        val day = LocalDate.of(2026, 11, 13)
        val today = LocalDate.of(2026, 11, 14)
        val note = GamesRepository.journalPath(day)
        val written = "# 2026-11-13\n\n## 🗓️ Timeline\n\n- 20:00 Dinner\n- 21:00 🎮 Ring of Ash · 1 h <!--game:Ring-->\n\n## 📝 Journal\n\n*No tasks*\n"
        disk[note] = written
        // The notes' mirror is empty (a fresh clone before its scan).
        coEvery { dao.pathsContaining(any()) } returns emptyList()
        assertFalse(repository.applyPastDay(day, today))
        assertEquals(written, disk[note])
        coEvery { dao.pathsContaining(any()) } answers { disk.filter { it.value.contains(firstArg<String>()) }.keys.toList() }
        // links.json doesn't parse.
        disk[GameFiles.LINKS] = "{not json"
        assertFalse(repository.applyPastDay(day, today))
        assertEquals(written, disk[note])
        disk.remove(GameFiles.LINKS)
        // The day's sessions find no page: the page renamed on the desktop.
        val renamed = "30_Areas/Games/Ring_Renamed/Ring_Renamed.md"
        disk[renamed] = disk.remove(pagePath)!!
        assertFalse(repository.applyPastDay(day, today))
        assertEquals(written, disk[note])
        // Back in place, the line follows its session.
        disk[pagePath] = disk.remove(renamed)!!
        assertTrue(repository.applyPastDay(day, today))
        assertEquals(written.replace("Ring of Ash · 1 h", "Ring of Ash · 2 h 35"), disk[note])
    }

    // --- 13.4: pages created and linked (GA3) ---------------------------------------------------

    @Test
    fun `a page differing only by case is never created`() = runTest {
        val today = LocalDate.of(2026, 11, 28)
        File(root, "30_Areas/Games/glass_tower").mkdirs()
        val tower = PagePlan("30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), listOf("1"), GameConsole.XBOX)
        assertTrue(repository.createPages(listOf(GamePageCreation.render(tower, null, null, today))).isEmpty())
        assertFalse(disk.containsKey(tower.path))
        // A file with another case in the right folder.
        File(root, "30_Areas/Games/Tin_Orchard").mkdirs()
        File(root, "30_Areas/Games/Tin_Orchard/TIN_ORCHARD.md").writeText("x")
        val orchard = PagePlan("30_Areas/Games/Tin_Orchard/Tin_Orchard.md", "Tin Orchard", emptyList(), listOf("2"), GameConsole.XBOX)
        assertTrue(repository.createPages(listOf(GamePageCreation.render(orchard, null, null, today))).isEmpty())
        // The same case all the way: created.
        File(root, "30_Areas/Games/Lantern_Bay").mkdirs()
        val bay = PagePlan("30_Areas/Games/Lantern_Bay/Lantern_Bay.md", "Lantern Bay", emptyList(), listOf("3"), GameConsole.XBOX)
        assertEquals(listOf(bay.path), repository.createPages(listOf(GamePageCreation.render(bay, null, null, today))).map { it.path })
    }

    @Test
    fun `createPageFor creates and links one title, refused while links can't be read`() = runTest {
        val today = LocalDate.of(2026, 11, 28)
        repository.saveXboxSnapshot(
            XboxSnapshot(
                "2026-11-27T22:40:00Z",
                titles = listOf(XboxTitleState("1915865690", "Glass Tower", achievementSource = 2, lastPlayed = "2026-11-27T21:00:00Z", visible = false))
            )
        )
        repository.saveAchievementSet(
            AchievementSetFile("1915865690", "Glass Tower", 2, listOf(AchievementRecord("1", "Ground Floor", gamerscore = 10, earnedAt = "2026-03-01T12:00:00Z")))
        )
        val created = repository.createPageFor(GameConsole.XBOX, "1915865690", today)!!
        assertEquals("30_Areas/Games/Glass_Tower/Glass_Tower.md", created.path)
        assertTrue(created.exactName)
        // Started on its first unlock, earlier than the last time it was played.
        assertEquals(LocalDate.of(2026, 3, 1), created.started)
        assertEquals(created.path, repository.links().consolePage(GameConsole.XBOX, "1915865690"))
        // Asked again: the page it is linked to.
        assertEquals(created.path, repository.createPageFor(GameConsole.XBOX, "1915865690", today)!!.path)
        // PlayStation's from its snapshot, started on its first play.
        repository.saveSnapshot(
            PsnSnapshot(
                "2026-11-27T22:40:00Z",
                listOf(
                    PsnTitleState("PPSA00002_00", "TIN ORCHARD", firstPlayed = "2026-02-01T12:00:00Z", lastPlayed = "2026-11-27T20:00:00Z"),
                    PsnTitleState("PPSA00003_00", "LANTERN BAY", lastPlayed = "2026-11-26T20:00:00Z")
                )
            )
        )
        val orchard = repository.createPageFor(GameConsole.PSN, "PPSA00002_00", today)!!
        assertEquals("30_Areas/Games/TIN_ORCHARD/TIN_ORCHARD.md", orchard.path)
        assertEquals(LocalDate.of(2026, 2, 1), orchard.started)
        assertEquals(orchard.path, repository.links().pageOf("PPSA00002_00"))
        // A title no snapshot knows gets none.
        assertNull(repository.createPageFor(GameConsole.PSN, "PPSA00009_00", today))
        // links.json unreadable: refused, nothing written.
        disk[GameFiles.LINKS] = "{not json"
        assertNull(repository.createPageFor(GameConsole.PSN, "PPSA00003_00", today))
        assertFalse(disk.keys.any { it.contains("LANTERN") })
        assertEquals("{not json", disk[GameFiles.LINKS])
        // A read-only device: refused.
        disk.remove(GameFiles.LINKS)
        VaultWriteGate.readOnly = true
        assertNull(repository.createPageFor(GameConsole.PSN, "PPSA00003_00", today))
        assertFalse(disk.keys.any { it.contains("LANTERN") })
    }

    @Test
    fun `createPageFor gives a title linked to a deleted page its page, and the link`() = runTest {
        val today = LocalDate.of(2026, 11, 28)
        repository.saveXboxSnapshot(
            XboxSnapshot(
                "2026-11-27T22:40:00Z",
                titles = listOf(XboxTitleState("1915865690", "Glass Tower", achievementSource = 2, lastPlayed = "2026-11-27T21:00:00Z"))
            )
        )
        // The page made for it was deleted on the desktop: the link dangles, and the fold lists the title.
        val gone = "30_Areas/Games/Glass_Tower_Old/Glass_Tower_Old.md"
        repository.updateLinks { it.linkConsole(GameConsole.XBOX, "1915865690", gone) }

        val created = repository.createPageFor(GameConsole.XBOX, "1915865690", today)!!

        assertEquals("30_Areas/Games/Glass_Tower/Glass_Tower.md", created.path)
        assertTrue(disk.containsKey(created.path))
        assertEquals(created.path, repository.links().consolePage(GameConsole.XBOX, "1915865690"))
        // A link to a page that is there still wins: asked again, that page, nothing new made.
        assertEquals(created.path, repository.createPageFor(GameConsole.XBOX, "1915865690", today)!!.path)
        assertEquals(2, repository.pages().size)
    }

    @Test
    fun `relink moves typed sessions to the new page`() = runTest {
        val gone = "30_Areas/Games/Old_Name/Old_Name.md"
        val folder = File(root, "${GameFiles.FOLDER}/sessions").apply { mkdirs() }
        File(folder, "2026-10.json").writeText("x")
        File(folder, "2026-11.json").writeText("x")
        repository.addSessions(
            listOf(
                GameSessionRecord("m1", "2026-10-05", "20:00", 30, GameSessionRecord.SOURCE_MANUAL, page = gone),
                GameSessionRecord("m2", "2026-11-14", "21:00", 60, GameSessionRecord.SOURCE_MANUAL, page = gone),
                GameSessionRecord("p1", "2026-11-14", "22:00", 45, GameSessionRecord.SOURCE_PSN, titleId = "PPSA1"),
                GameSessionRecord("m3", "2026-11-15", "20:00", 20, GameSessionRecord.SOURCE_MANUAL, page = pagePath)
            )
        )
        assertEquals(setOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 14)), repository.relinkManualSessions(gone, pagePath))
        val all = repository.allSessions().associateBy { it.id }
        assertEquals(pagePath, all.getValue("m1").page)
        assertEquals(pagePath, all.getValue("m2").page)
        assertNull(all.getValue("p1").page)
        assertEquals(pagePath, all.getValue("m3").page)
        assertTrue(OrphanedSessions.of(repository.allSessions(), repository.pages()).isEmpty())
        // Nothing left to move.
        assertEquals(emptySet<LocalDate>(), repository.relinkManualSessions(gone, pagePath))
        VaultWriteGate.readOnly = true
        assertNull(repository.relinkManualSessions(pagePath, gone))
    }

    // --- 13.4: the owner's game pictures (P5) ----------------------------------------------------

    @Test
    fun `covers resolve to files, a picture is set and removed, nothing on a read-only device`() = runTest {
        val own = "30_Areas/Games/Ring/Ring (cover).jpg"
        assertEquals(CoverEditResult.SAVED, repository.setCover(pagePath, byteArrayOf(1, 2, 3), LocalDate.of(2026, 11, 28)))
        assertArrayEquals(byteArrayOf(1, 2, 3), pictures[own])
        assertTrue(disk.getValue(pagePath).contains("\ngame_cover: \"[[Ring (cover).jpg]]\"\n"))
        assertTrue(disk.getValue(pagePath).contains("date_updated: '2026-11-28'"))
        verify(atLeast = 2) { push.requestPush() }
        val page = repository.page(pagePath)!!
        assertEquals("Ring (cover).jpg", page.cover)
        assertEquals(mapOf(pagePath to File(root, own)), repository.coverFiles(listOf(page)))
        // No cover, or one the vault doesn't hold: none.
        assertTrue(repository.coverFiles(listOf(page.copy(cover = null))).isEmpty())
        assertTrue(repository.coverFiles(listOf(page.copy(cover = "missing.jpg"))).isEmpty())
        // Another picture: the file replaced, the page as it was.
        val pageText = disk.getValue(pagePath)
        assertEquals(CoverEditResult.SAVED, repository.setCover(pagePath, byteArrayOf(4, 5), LocalDate.of(2026, 12, 1)))
        assertEquals(pageText, disk.getValue(pagePath))
        assertArrayEquals(byteArrayOf(4, 5), pictures[own])
        // Removed: the key and the app's own file.
        assertEquals(CoverEditResult.SAVED, repository.removeCover(pagePath, LocalDate.of(2026, 12, 2)))
        assertFalse(disk.getValue(pagePath).contains("game_cover"))
        assertFalse(pictures.containsKey(own))
        assertEquals(CoverEditResult.NOTHING_TO_REMOVE, repository.removeCover(pagePath))
        // A picture the owner named by hand stays when its key goes.
        pictures["Assets/mine.png"] = byteArrayOf(9)
        attachments += "Assets/mine.png"
        disk[pagePath] = disk.getValue(pagePath).replace("game_template: souls-like\n", "game_template: souls-like\ngame_cover: \"[[mine.png]]\"\n")
        assertEquals(mapOf(pagePath to File(root, "Assets/mine.png")), repository.coverFiles(listOf(repository.page(pagePath)!!)))
        assertEquals(CoverEditResult.SAVED, repository.removeCover(pagePath))
        assertTrue(pictures.containsKey("Assets/mine.png"))
        // A page that's gone.
        assertEquals(CoverEditResult.GONE, repository.setCover("30_Areas/Games/Gone/Gone.md", byteArrayOf(1)))
        assertEquals(CoverEditResult.GONE, repository.removeCover("30_Areas/Games/Gone/Gone.md"))
        assertFalse(pictures.keys.any { it.contains("Gone") })
        // A read-only device writes nothing, the pictures only read.
        repository.setCover(pagePath, byteArrayOf(7), LocalDate.of(2026, 12, 3))
        VaultWriteGate.readOnly = true
        val untouched = disk.toMap()
        val picturesBefore = pictures.keys.toSet()
        assertEquals(CoverEditResult.READ_ONLY, repository.setCover(pagePath, byteArrayOf(8)))
        assertEquals(CoverEditResult.READ_ONLY, repository.removeCover(pagePath))
        assertEquals(untouched, disk)
        assertEquals(picturesBefore, pictures.keys)
        assertArrayEquals(byteArrayOf(7), pictures[own])
        assertEquals(mapOf(pagePath to File(root, own)), repository.coverFiles(listOf(repository.page(pagePath)!!)))
    }
}
