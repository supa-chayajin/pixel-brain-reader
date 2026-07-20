package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.GratitudeDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ScratchDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Repository-level safety net for the destructive-Room-migration recovery path:
 * a populated board is burned to markdown by [DailyDashboardRepository.burnToDisk], Room is
 * wiped (fresh mocks = `fallbackToDestructiveMigration`), and
 * [DailyDashboardRepository.rehydrateTodayFromDiskIfEmpty] must rebuild the SAME items from
 * that markdown — with the Google external keys intact (the triple-dup regression).
 *
 * Also pins the two guards around it:
 *  - rehydrate is a strict no-op when Room already holds today (never clobbers live state);
 *  - the ingest SHIELD blocks any file ingest for TODAY entirely (zero DAO writes).
 *
 * The repository uses `LocalDate.now()` internally (no injectable clock), so all fixtures
 * are anchored on the real today; only a run crossing midnight mid-test could flake.
 */
class DailyDashboardRepositoryRehydrateTest {

    private val today: LocalDate = LocalDate.now()
    private val todayStr: String = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val journalPath: String = "10_Journal/${today.format(DateTimeFormatter.ISO_DATE)}.md"

    // --- Fixtures: a populated "day in Room" -------------------------------------------

    private val roomDashboard = DailyDashboardEntity(
        date = today,
        dailyMantra = "Focus.", // LOSSY across the cycle — asserted below.
        ideasContent = "Persist this idea",
        notesContent = "Persist this note"
    )
    private val roomTimeline = listOf(
        TimelineEntryEntity(
            date = today, time = LocalTime.of(9, 0),
            content = "[Work] Standup", googleEventId = "evt_google_1"
        ),
        TimelineEntryEntity(date = today, time = LocalTime.of(11, 30), content = "Local errand")
    )
    private val roomTasks = listOf(
        DailyTaskEntity(
            scheduledDate = todayStr, label = "Google chore",
            googleTaskId = "gtask_1", source = "GoogleTasks"
        ),
        DailyTaskEntity(
            scheduledDate = todayStr, label = "Write report",
            isDone = true, priority = 2, scheduledTime = "10:15"
        )
    )
    private val roomScraps = listOf(
        ScratchNoteEntity(content = "unprocessed scrap A"),
        ScratchNoteEntity(content = "unprocessed scrap B")
    )
    private val roomGratitudes = listOf(GratitudeEntity(date = todayStr, content = "Sunny weather"))

    // --- Mock harness -------------------------------------------------------------------

    private class Harness {
        val dashboardDao: DailyDashboardDao = mockk()
        val scratchDao: ScratchDao = mockk()
        val fileRepository: FileRepository = mockk()
        val gratitudeDao: GratitudeDao = mockk()
        val repository = DailyDashboardRepository(
            dashboardDao = dashboardDao,
            scratchDao = scratchDao,
            fileRepository = fileRepository,
            briefingGenerator = mockk<BriefingGenerator>(relaxed = true),
            weatherRepository = mockk<WeatherRepository>(relaxed = true),
            gratitudeDao = gratitudeDao,
            widgetUpdateManager = mockk(relaxed = true)
        )
    }

    /** Wires a harness so burnToDisk sees the populated Room fixtures; returns the captured markdown slot. */
    private fun stubPopulatedBurn(h: Harness, existingFileContent: String? = null): io.mockk.CapturingSlot<String> {
        coEvery { h.dashboardDao.getDashboard(today) } returns roomDashboard
        coEvery { h.dashboardDao.getTimelineSnapshot(today) } returns roomTimeline
        coEvery { h.dashboardDao.getTasksSnapshot(today) } returns roomTasks
        coEvery { h.fileRepository.readFile(journalPath) } returns existingFileContent
        coEvery { h.scratchDao.getActiveScrapsSync() } returns roomScraps
        coEvery { h.gratitudeDao.getGratitudesForDateOneShot(todayStr) } returns roomGratitudes
        val written = slot<String>()
        coEvery { h.fileRepository.saveFileLocally(journalPath, capture(written)) } returns Unit
        return written
    }

    private fun burnMarkdown(): String = runBlockingBurn()

    private fun runBlockingBurn(): String {
        val h = Harness()
        val written = stubPopulatedBurn(h)
        kotlinx.coroutines.runBlocking { h.repository.burnToDisk(today) }
        return written.captured
    }

    // --- 1. burnToDisk writes the identity-complete markdown -----------------------------

    @Test
    fun `burnToDisk writes today's board to the journal path with external keys embedded`() = runTest {
        val h = Harness()
        val written = stubPopulatedBurn(h)

        h.repository.burnToDisk(today)

        coVerify(exactly = 1) { h.fileRepository.saveFileLocally(journalPath, any()) }
        val md = written.captured
        // The vault file carries the invisible identity markers — the triple-dup fix.
        assertTrue(md.contains("<!--gcal:evt_google_1-->"))
        assertTrue(md.contains("<!--gtask:gtask_1-->"))
        assertTrue(md.contains("[Work] Standup"))
        assertTrue(md.contains("Persist this idea"))
        assertTrue(md.contains("Persist this note"))
        assertTrue(md.contains("unprocessed scrap A"))
        assertTrue(md.contains("Sunny weather"))
    }

    @Test
    fun `burnToDisk is a no-op when Room has no dashboard for the date`() = runTest {
        val h = Harness()
        coEvery { h.dashboardDao.getDashboard(today) } returns null

        h.repository.burnToDisk(today)

        coVerify(exactly = 0) { h.fileRepository.saveFileLocally(any(), any()) }
        verify { h.fileRepository wasNot Called }
    }

    // --- 2. Full cycle: burn → wipe Room → rehydrate reconstructs the same items ---------

    @Test
    fun `burn then wipe then rehydrate reconstructs the same board including google keys`() = runTest {
        // Phase 1 — populated Room, burn to disk.
        val burned = burnMarkdown()

        // Phase 2 — a destructive migration wiped Room: fresh mocks, empty DB, disk intact.
        val h2 = Harness()
        coEvery { h2.dashboardDao.getDashboard(today) } returns null
        coEvery { h2.fileRepository.readFile(journalPath) } returns burned

        val dashSlot = slot<DailyDashboardEntity>()
        val timelineSlot = slot<List<TimelineEntryEntity>>()
        val tasksSlot = slot<List<DailyTaskEntity>>()
        coEvery {
            h2.dashboardDao.ingestDailyData(capture(dashSlot), capture(timelineSlot), capture(tasksSlot))
        } returns Unit
        val scrapInserts = mutableListOf<ScratchNoteEntity>()
        coEvery { h2.scratchDao.insertScrap(capture(scrapInserts)) } returns Unit
        val gratitudeInserts = mutableListOf<GratitudeEntity>()
        coEvery { h2.gratitudeDao.insertGratitude(capture(gratitudeInserts)) } returns Unit

        h2.repository.rehydrateTodayFromDiskIfEmpty()

        coVerify(exactly = 1) { h2.dashboardDao.ingestDailyData(any(), any(), any()) }

        // Dashboard scalar sections survive; the mantra is a pinned loss (rebuilt as "").
        assertEquals(today, dashSlot.captured.date)
        assertEquals("Persist this idea", dashSlot.captured.ideasContent)
        assertEquals("Persist this note", dashSlot.captured.notesContent)
        assertEquals("LOSSY PIN: rehydrate hardcodes dailyMantra to empty", "", dashSlot.captured.dailyMantra)

        // Timeline rows reconstructed 1:1 — including the Calendar identity key.
        val timeline = timelineSlot.captured
        assertEquals(2, timeline.size)
        val tlByContent = timeline.associateBy { it.content }
        assertEquals("evt_google_1", tlByContent.getValue("[Work] Standup").googleEventId)
        assertEquals(LocalTime.of(9, 0), tlByContent.getValue("[Work] Standup").time)
        assertNull(tlByContent.getValue("Local errand").googleEventId)
        assertEquals(LocalTime.of(11, 30), tlByContent.getValue("Local errand").time)
        assertTrue(timeline.all { it.date == today })
        assertFalse("Markers must never leak into content", timeline.any { it.content.contains("<!--") })

        // Task rows reconstructed 1:1 — including the Google Tasks identity key + source.
        val tasks = tasksSlot.captured
        assertEquals(2, tasks.size)
        val taskByLabel = tasks.associateBy { it.label }
        val chore = taskByLabel.getValue("Google chore")
        assertEquals("gtask_1", chore.googleTaskId)
        assertEquals("GoogleTasks", chore.source)
        assertEquals(false, chore.isDone)
        val report = taskByLabel.getValue("Write report")
        assertNull(report.googleTaskId)
        assertEquals("Local", report.source)
        assertEquals(true, report.isDone)
        assertEquals(2, report.priority)
        assertEquals("10:15", report.scheduledTime)
        assertTrue(tasks.all { it.scheduledDate == todayStr })

        // Scraps and gratitude are restored (content-level identity; fresh UUIDs by design).
        assertEquals(
            listOf("unprocessed scrap A", "unprocessed scrap B"),
            scrapInserts.map { it.content }
        )
        assertEquals(listOf("Sunny weather"), gratitudeInserts.map { it.content })
        assertTrue(gratitudeInserts.all { it.date == todayStr })
    }

    @Test
    fun `rehydrating twice from the same file does not multiply google-keyed rows`() = runTest {
        // The triple-dup signature was "one more copy per rehydrate". Two consecutive
        // rehydrates over the same burned file must hand Room identical, deduped row sets
        // (Room-side REPLACE on the unique google keys then makes re-ingest idempotent).
        val burned = burnMarkdown()

        repeat(2) {
            val h = Harness()
            coEvery { h.dashboardDao.getDashboard(today) } returns null
            coEvery { h.fileRepository.readFile(journalPath) } returns burned
            val timelineSlot = slot<List<TimelineEntryEntity>>()
            val tasksSlot = slot<List<DailyTaskEntity>>()
            coEvery { h.dashboardDao.ingestDailyData(any(), capture(timelineSlot), capture(tasksSlot)) } returns Unit
            coEvery { h.scratchDao.insertScrap(any()) } returns Unit
            coEvery { h.gratitudeDao.insertGratitude(any()) } returns Unit

            h.repository.rehydrateTodayFromDiskIfEmpty()

            assertEquals(listOf("evt_google_1"), timelineSlot.captured.mapNotNull { it.googleEventId })
            assertEquals(listOf("gtask_1"), tasksSlot.captured.mapNotNull { it.googleTaskId })
            assertEquals(2, timelineSlot.captured.size)
            assertEquals(2, tasksSlot.captured.size)
        }
    }

    // --- 3. Guards: rehydrate no-op + ingest SHIELD ---------------------------------------

    @Test
    fun `rehydrate is a strict no-op when Room already holds today`() = runTest {
        val h = Harness()
        coEvery { h.dashboardDao.getDashboard(today) } returns roomDashboard

        h.repository.rehydrateTodayFromDiskIfEmpty()

        // Never reads disk, never writes a single row — live state is untouchable.
        verify { h.fileRepository wasNot Called }
        verify { h.scratchDao wasNot Called }
        verify { h.gratitudeDao wasNot Called }
        coVerify(exactly = 0) { h.dashboardDao.ingestDailyData(any(), any(), any()) }
        coVerify(exactly = 1) { h.dashboardDao.getDashboard(today) }
    }

    @Test
    fun `ingest SHIELD blocks any file ingest for today - zero DAO writes`() = runTest {
        val h = Harness()
        val pulledContent = """
            # $todayStr

            ## 🗓️ Timeline

            - 09:00 Poisoned event from a git pull
        """.trimIndent()

        h.repository.ingest(today, pulledContent)

        // The SHIELD returns before touching ANY collaborator: existing Room rows
        // (the user's live typing) can never be clobbered by a pulled file.
        verify { h.dashboardDao wasNot Called }
        verify { h.scratchDao wasNot Called }
        verify { h.gratitudeDao wasNot Called }
        verify { h.fileRepository wasNot Called }
    }

    @Test
    fun `ingest for a past day parses and ingests but never touches scraps or gratitude`() = runTest {
        val h = Harness()
        val pastDate = today.minusDays(3)
        val pastStr = pastDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val content = """
            # $pastStr

            ## 🗓️ Timeline

            - 09:00 Old standup <!--gcal:evt_old-->

            ## 📝 Journal

            - [x] Old chore <!--gtask:gtask_old-->

            ## 💡 Scraps (Unprocessed)

            - old scrap that must NOT be re-inserted
        """.trimIndent()

        val dashSlot = slot<DailyDashboardEntity>()
        val timelineSlot = slot<List<TimelineEntryEntity>>()
        val tasksSlot = slot<List<DailyTaskEntity>>()
        coEvery {
            h.dashboardDao.ingestDailyData(capture(dashSlot), capture(timelineSlot), capture(tasksSlot))
        } returns Unit

        h.repository.ingest(pastDate, content)

        coVerify(exactly = 1) { h.dashboardDao.ingestDailyData(any(), any(), any()) }
        assertEquals(pastDate, dashSlot.captured.date)
        assertEquals("evt_old", timelineSlot.captured.single().googleEventId)
        assertEquals("gtask_old", tasksSlot.captured.single().googleTaskId)
        // Documented contract (DailyDashboardRepository.kt:170-174): past-day ingest must
        // NOT re-insert scraps/gratitude — their UUID PKs would duplicate on every reconcile.
        verify { h.scratchDao wasNot Called }
        verify { h.gratitudeDao wasNot Called }
    }

    // --- 4. Re-burn over an existing file: board data still survives ----------------------

    @Test
    fun `re-burning over yesterday's file keeps the board parseable and identical`() = runTest {
        // First burn (no file yet on disk).
        val firstBurn = burnMarkdown()

        // Second burn over the existing file — burnToDisk re-reads it for the frontmatter.
        // KNOWN QUIRK (documented, not endorsed): burnToDisk passes
        // FrontmatterManager.extractFrontmatterRaw() — the YAML body WITHOUT its `---`
        // fences — into MarkdownBurner.burn, which appends it verbatim, so the second file
        // starts with a bare `date:` line instead of a delimited frontmatter block. We pin
        // only the invariant that matters for recovery: the parsed BOARD is unchanged.
        val h2 = Harness()
        val secondWritten = stubPopulatedBurn(h2, existingFileContent = firstBurn)
        h2.repository.burnToDisk(today)
        val secondBurn = secondWritten.captured

        assertTrue("The date frontmatter key must survive a re-burn", secondBurn.contains("date: $todayStr"))

        val p1 = DailyMarkdownParser.parse(today, firstBurn)
        val p2 = DailyMarkdownParser.parse(today, secondBurn)
        assertEquals(p1.timeline.map { Triple(it.time, it.content, it.googleEventId) },
            p2.timeline.map { Triple(it.time, it.content, it.googleEventId) })
        assertEquals(p1.tasks.map { listOf(it.label, it.scheduledTime, it.googleTaskId, it.source, it.isDone.toString()) },
            p2.tasks.map { listOf(it.label, it.scheduledTime, it.googleTaskId, it.source, it.isDone.toString()) })
        assertEquals(p1.ideas, p2.ideas)
        assertEquals(p1.notes, p2.notes)
        assertEquals(p1.scraps, p2.scraps)
        assertEquals(p1.gratitude, p2.gratitude)
    }
}
