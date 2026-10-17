package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MemoryEchoRepositoryTest {

    private val now = 1_800_000_000_000L
    private fun daysAgo(days: Long) = now - days * 24 * 60 * 60 * 1000

    private val fileDao: FileDao = mockk()
    private val fileContentDao: FileContentDao = mockk { coEvery { getContent(any()) } returns "Some content." }
    private val gitFileAges: GitFileAges = mockk()
    private val reviews = mutableMapOf<String, Long>()
    private val echoReviewStore: EchoReviewStore = mockk {
        coEvery { reviewedAt() } answers { reviews.toMap() }
        coEvery { markReviewed(any(), any()) } answers { reviews[firstArg()] = secondArg() }
    }
    private val today = MutableStateFlow(LocalDate.of(2026, 10, 17))
    private val appClock: AppClock = mockk { every { this@mockk.today } returns this@MemoryEchoRepositoryTest.today }
    private val repository = MemoryEchoRepository(fileDao, fileContentDao, gitFileAges, echoReviewStore, appClock)

    private fun note(path: String, mtime: Long? = now) =
        FileEntity(path = path, name = path.substringAfterLast('/'), type = "file", downloadUrl = null, localModifiedTimestamp = mtime)

    @Test
    fun `age comes from git, not from the mtime a clone or pull rewrote`() = runTest {
        // Every file was just re-cloned (fresh mtime), but git knows how old the notes really are.
        every { fileDao.getAllFiles() } returns flowOf(
            listOf(
                note("20_Projects/21_Waffle/Architecture.md"),
                note("30_Areas/Recent.md"),
                note("10_Journal/2026-09-01.md"),
                note("99_Private/diary.md.enc"),
                note("40_Resources/Uncommitted.md")
            )
        )
        coEvery { gitFileAges.lastChanged() } returns mapOf(
            "20_Projects/21_Waffle/Architecture.md" to daysAgo(40),
            "30_Areas/Recent.md" to daysAgo(5),
            "10_Journal/2026-09-01.md" to daysAgo(46),
            "99_Private/diary.md.enc" to daysAgo(90)
        )

        val echoes = repository.getDailyEchoes(limit = 5, now = now)

        assertEquals(listOf("20_Projects/21_Waffle/Architecture.md"), echoes.map { it.path })
        assertEquals(40L, echoes.single().daysSinceModified)
        assertEquals("Untouched for 40 days", echoes.single().urgencyLabel)
        assertEquals("21_Waffle", echoes.single().categoryBadge)
    }

    @Test
    fun `a reviewed note rests for two weeks, then can echo again`() = runTest {
        val path = "30_Areas/Hardening.md"
        every { fileDao.getAllFiles() } returns flowOf(listOf(note(path)))
        coEvery { gitFileAges.lastChanged() } returns mapOf(path to daysAgo(30))

        assertEquals(1, repository.getDailyEchoes(now = now).size)
        repository.markEchoReviewed(path, now = now)
        coVerify { echoReviewStore.markReviewed(path, now) }

        assertTrue(repository.getDailyEchoes(now = now + 13 * 24 * 60 * 60 * 1000L).isEmpty())
        assertEquals(1, repository.getDailyEchoes(now = now + 15 * 24 * 60 * 60 * 1000L).size)
    }

    @Test
    fun `the selection rotates daily among the most forgotten notes`() = runTest {
        val paths = (1..10).map { "40_Resources/note$it.md" }
        every { fileDao.getAllFiles() } returns flowOf(paths.map { note(it) })
        coEvery { gitFileAges.lastChanged() } returns paths.associateWith { daysAgo(100) }

        val days = (0L..6L).map { offset ->
            today.value = LocalDate.of(2026, 10, 17).plusDays(offset)
            repository.getDailyEchoes(limit = 2, now = now).map { it.path }.toSet()
        }

        assertTrue("a week of echoes should not repeat the same pair every day", days.toSet().size > 1)
    }
}
