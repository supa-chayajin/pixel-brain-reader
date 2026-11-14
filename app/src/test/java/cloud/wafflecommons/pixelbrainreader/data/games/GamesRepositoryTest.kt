package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.coEvery
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
}
