package cloud.wafflecommons.pixelbrainreader.data.workers

import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Settled decision #3 (CLAUDE.md): today's journal and 99_System are never embedded; people notes
 * never either, nor Claude's memory mirrored from the desktop (owner decision D2).
 */
class IndexingWorkerCandidatesTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun file(path: String, type: String = "file") =
        FileEntity(path = path, name = path.substringAfterLast('/'), type = type, downloadUrl = null)

    private val vault = listOf(
        file("10_Journal/2026-10-17.md"), // today
        file("10_Journal/2026-10-16.md"),
        file("20_Projects/plan.md"),
        file("99_System/gamification.md"),
        file("99_Private/diary.md.enc"),
        file("99_Private/People/alias-a.md.enc"),
        file("assets/photo.png"),
        file("20_Projects", type = "dir")
    )

    @Test
    fun `today's journal, system files, attachments and folders are skipped`() {
        val candidates = IndexingWorker.embeddingCandidates(vault, today, hasVaultPassword = false)
        assertEquals(listOf("10_Journal/2026-10-16.md", "20_Projects/plan.md"), candidates.map { it.path })
    }

    @Test
    fun `people notes are never embedded, even with the vault password stored`() {
        val candidates = IndexingWorker.embeddingCandidates(vault, today, hasVaultPassword = true)
        assertEquals(false, candidates.any { it.path.startsWith("99_Private/People/") })
    }

    @Test
    fun `private notes join only when a vault password is stored`() {
        val candidates = IndexingWorker.embeddingCandidates(vault, today, hasVaultPassword = true)
        assertEquals(
            listOf("10_Journal/2026-10-16.md", "20_Projects/plan.md", "99_Private/diary.md.enc"),
            candidates.map { it.path }
        )
    }

    @Test
    fun `claude's mirrored memory is left out, the mirrored repository documents stay`() {
        val mirrors = listOf(
            file("20_Projects/21_Demo/Claude/Memory/feedback.md"),
            file("20_Projects/21_Demo/Repo/Roadmaps/Roadmap_v2.md"),
            file("20_Projects/21_Demo/Claude/Reports/2026-11-06_Audit.md")
        )
        val candidates = IndexingWorker.embeddingCandidates(mirrors, today, hasVaultPassword = true)
        assertEquals(
            listOf("20_Projects/21_Demo/Repo/Roadmaps/Roadmap_v2.md", "20_Projects/21_Demo/Claude/Reports/2026-11-06_Audit.md"),
            candidates.map { it.path }
        )
    }

    @Test
    fun `only the vectors of notes that must stay out are purged`() {
        val embedded = listOf(
            "20_Projects/21_Demo/Claude/Memory/feedback.md",
            "20_Projects/21_Demo/Repo/Docs/guide.md",
            "10_Journal/2026-10-17.md" // today: not embedded today, but never purged
        )
        assertEquals(listOf("20_Projects/21_Demo/Claude/Memory/feedback.md"), IndexingWorker.neverEmbedded(embedded))
    }
}
