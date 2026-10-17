package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultGraphAnalyzerTest {

    private lateinit var fileDao: FileDao
    private lateinit var fileContentDao: FileContentDao
    private lateinit var analyzer: VaultGraphAnalyzer

    @Before
    fun setUp() {
        fileDao = mockk()
        fileContentDao = mockk()
        analyzer = VaultGraphAnalyzer(fileDao, fileContentDao)
    }

    @Test
    fun analyze_emptyVault_returnsPerfectHealthScore() = runBlocking {
        coEvery { fileDao.getAllFiles() } returns flowOf(emptyList())

        val report = analyzer.analyze()

        assertEquals(0, report.totalNotesAnalyzed)
        assertEquals(0, report.totalWikiLinksFound)
        assertTrue(report.deadLinks.isEmpty())
        assertTrue(report.orphanNotes.isEmpty())
        assertEquals(100, report.healthScore)
    }

    @Test
    fun analyze_validLinksAndDeadLinks_detectsDeadLinksCorrectly() = runBlocking {
        val files = listOf(
            FileEntity("20_Projects/Waffle.md", "Waffle.md", "file", null),
            FileEntity("20_Projects/Closd.md", "Closd.md", "file", null),
            FileEntity("30_Areas/Architecture.md", "Architecture.md", "file", null)
        )
        coEvery { fileDao.getAllFiles() } returns flowOf(files)

        // Waffle links to Closd (valid) and NonExistentNote (dead)
        coEvery { fileContentDao.getContent("20_Projects/Waffle.md") } returns
            "# Waffle\nWorking with [[Closd]] on new features.\nSee also [[NonExistentNote|Alias]]."

        // Closd links to Architecture with section anchor (valid)
        coEvery { fileContentDao.getContent("20_Projects/Closd.md") } returns
            "# Closd\nFollows [[Architecture#Clean Code]]."

        // Architecture has no links
        coEvery { fileContentDao.getContent("30_Areas/Architecture.md") } returns
            "# Architecture\nDDD guidelines."

        val report = analyzer.analyze()

        assertEquals(3, report.totalNotesAnalyzed)
        assertEquals(3, report.totalWikiLinksFound)
        assertEquals(1, report.deadLinks.size)
        assertEquals("NonExistentNote", report.deadLinks[0].targetName)
        assertEquals("20_Projects/Waffle.md", report.deadLinks[0].sourcePath)
        // Architecture has incoming link from Closd, so it's not orphan.
        // Closd has incoming link from Waffle, and outgoing link, so not orphan.
        // Waffle has outgoing link, so not orphan.
        assertTrue(report.orphanNotes.isEmpty())
        // Score = healthy notes: Waffle has a dead link, the other two are fine → 2/3.
        assertEquals(67, report.healthScore)
    }

    @Test
    fun analyze_embedsAttachmentsAndCodeAreNotDeadLinks() = runBlocking {
        val files = listOf(
            FileEntity("20_Projects/Plan.md", "Plan.md", "file", null),
            FileEntity("20_Projects/Other.md", "Other.md", "file", null),
            FileEntity("assets/diagram.png", "diagram.png", "file", null),
            FileEntity("20_Projects/spec.pdf", "spec.pdf", "file", null)
        )
        coEvery { fileDao.getAllFiles() } returns flowOf(files)
        coEvery { fileContentDao.getContent("20_Projects/Plan.md") } returns """
            # Plan
            ![[diagram.png]] and ![[missing-image.png]] are embeds, not links.
            The spec is [[spec.pdf]]; see [[Other]].
            ```
            [[Inside a code block]]
            ```
        """.trimIndent()
        coEvery { fileContentDao.getContent("20_Projects/Other.md") } returns "# Other\nBack to [[Plan]]."

        val report = analyzer.analyze()

        assertTrue(report.deadLinks.isEmpty())
        assertEquals(3, report.totalWikiLinksFound) // spec.pdf, Other, Plan
        assertEquals(100, report.healthScore)
    }

    @Test
    fun analyze_orphanNoteDetected_journalExcluded() = runBlocking {
        val files = listOf(
            FileEntity("20_Projects/Active.md", "Active.md", "file", null),
            FileEntity("40_Resources/UnlinkedResource.md", "UnlinkedResource.md", "file", null),
            FileEntity("10_Journal/2026-10-17.md", "2026-10-17.md", "file", null)
        )
        coEvery { fileDao.getAllFiles() } returns flowOf(files)

        coEvery { fileContentDao.getContent("20_Projects/Active.md") } returns
            "# Active\nSelf-referencing [[Active]]."

        coEvery { fileContentDao.getContent("40_Resources/UnlinkedResource.md") } returns
            "# Resource\nNo links at all here."

        coEvery { fileContentDao.getContent("10_Journal/2026-10-17.md") } returns
            "# Daily\nJust thoughts, no links."

        val report = analyzer.analyze()

        // 40_Resources/UnlinkedResource.md is an orphan.
        // 10_Journal is excluded from orphans by design.
        assertEquals(1, report.orphanNotes.size)
        assertEquals("40_Resources/UnlinkedResource.md", report.orphanNotes[0])
    }
}
