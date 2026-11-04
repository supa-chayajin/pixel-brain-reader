package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.LinkDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.PathContent
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ProjectRepositoryTest {

    private val today = LocalDate.of(2026, 11, 4)
    private val fileContentDao = mockk<FileContentDao>()
    private val linkDao = mockk<LinkDao>()
    private val visibility = MutableStateFlow(Visibility())
    private val modes = mockk<ModesRepository> { every { visibility } returns this@ProjectRepositoryTest.visibility }
    private val repo = ProjectRepository(fileContentDao, linkDao, modes)

    private var signature = "2:1:10"

    init {
        coEvery { fileContentDao.signatureUnder(any()) } answers { signature }
        coEvery { linkDao.signature() } returns "0:0"
        coEvery { linkDao.allNoteLinks() } returns emptyList()
        coEvery { fileContentDao.contentsUnder("20_Projects/") } returns listOf(
            PathContent("20_Projects/21_Demo/README.md", "---\ntitle: Demo\n---\n"),
            PathContent("30_Work/x.md", "ignored"),
            PathContent("20_Projects/28_Job/README.md", "---\ntitle: Job\n---\n")
        )
    }

    @Test
    fun `summaries are cached until the project notes change`() = runTest {
        assertEquals(2, repo.projects(today).size)
        repo.projects(today)
        repo.project("20_Projects/21_Demo", today)
        coVerify(exactly = 1) { fileContentDao.contentsUnder(any()) }

        signature = "2:2:12" // a note was rewritten by the vault scan
        repo.projects(today)
        coVerify(exactly = 2) { fileContentDao.contentsUnder(any()) }

        repo.projects(today.plusDays(1)) // the 14-day window moved
        coVerify(exactly = 3) { fileContentDao.contentsUnder(any()) }
    }

    @Test
    fun `work projects are filtered from the cache while work is hidden`() = runTest {
        visibility.value = Visibility(mode = ContextMode.PERSONAL, workFolders = listOf("20_Projects/28_Job"))
        assertEquals(listOf("20_Projects/21_Demo"), repo.projects(today).map { it.root })
        assertNull(repo.project("20_Projects/28_Job", today))

        visibility.value = Visibility(mode = ContextMode.WORK, workFolders = listOf("20_Projects/28_Job"))
        assertEquals(2, repo.projects(today).size)
        coVerify(exactly = 1) { fileContentDao.contentsUnder(any()) }
    }
}
