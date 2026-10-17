package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MemoryEchoRepositoryTest {

    private val fileDao: FileDao = mockk()
    private val fileContentDao: FileContentDao = mockk()
    private val appClock: AppClock = mockk()
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)

    private val reviewedEchoesFlow = MutableStateFlow("")
    private lateinit var repository: MemoryEchoRepository

    private val now = System.currentTimeMillis()
    private val fifteenDaysAgo = now - (15L * 24 * 60 * 60 * 1000)
    private val fiveDaysAgo = now - (5L * 24 * 60 * 60 * 1000)
    private val fortyDaysAgo = now - (40L * 24 * 60 * 60 * 1000)

    @Before
    fun setUp() {
        reviewedEchoesFlow.value = ""
        every { userPreferencesRepository.reviewedEchoes } returns reviewedEchoesFlow
        coEvery { userPreferencesRepository.recordEchoReviewed(any()) } answers {
            val path = firstArg<String>()
            val current = reviewedEchoesFlow.value
            reviewedEchoesFlow.value = if (current.isEmpty()) "$path:$now" else "$current,$path:$now"
        }
        every { appClock.today } returns MutableStateFlow(LocalDate.of(2026, 10, 17))

        repository = MemoryEchoRepository(
            fileDao = fileDao,
            fileContentDao = fileContentDao,
            userPreferencesRepository = userPreferencesRepository,
            appClock = appClock
        )
    }

    @Test
    fun `getDailyEchoes excludes notes younger than 14 days and journal or private notes`() = runTest {
        val files = listOf(
            FileEntity(
                path = "20_Projects/21_Waffle_Framework/Architecture.md",
                name = "Architecture.md",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fortyDaysAgo
            ),
            FileEntity(
                path = "20_Projects/22_Closd/RecentSpecs.md",
                name = "RecentSpecs.md",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fiveDaysAgo // Too recent (< 14 days)
            ),
            FileEntity(
                path = "10_Journal/2026-09-01.md",
                name = "2026-09-01.md",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fortyDaysAgo // Excluded (journal)
            ),
            FileEntity(
                path = "99_Private/Secrets.md.enc",
                name = "Secrets.md.enc",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fortyDaysAgo // Excluded (private encrypted)
            ),
            FileEntity(
                path = "30_Areas/DevSecOps/Hardening.md",
                name = "Hardening.md",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fifteenDaysAgo
            )
        )

        every { fileDao.getAllFiles() } returns flowOf(files)
        coEvery { fileContentDao.getContent("20_Projects/21_Waffle_Framework/Architecture.md") } returns
            "---\ntags: [arch, waffle]\n---\n# Architecture\nClean architecture core domain boundaries."
        coEvery { fileContentDao.getContent("30_Areas/DevSecOps/Hardening.md") } returns
            "## DevSecOps\nContainer hardening guidelines and CIS benchmarks."

        val echoes = repository.getDailyEchoes(limit = 5)

        assertEquals(2, echoes.size)
        val paths = echoes.map { it.path }
        assertTrue(paths.contains("20_Projects/21_Waffle_Framework/Architecture.md"))
        assertTrue(paths.contains("30_Areas/DevSecOps/Hardening.md"))

        val waffleEcho = echoes.first { it.path == "20_Projects/21_Waffle_Framework/Architecture.md" }
        assertEquals("Architecture", waffleEcho.title)
        assertEquals("21_Waffle_Framework", waffleEcho.categoryBadge)
        assertTrue(waffleEcho.snippet.contains("Clean architecture core domain boundaries"))
        assertTrue("Snippet should strip frontmatter", !waffleEcho.snippet.contains("tags:"))
    }

    @Test
    fun `markEchoReviewed suppresses note from resurfacing`() = runTest {
        val files = listOf(
            FileEntity(
                path = "20_Projects/21_Waffle_Framework/Architecture.md",
                name = "Architecture.md",
                type = "file",
                downloadUrl = null,
                localModifiedTimestamp = fortyDaysAgo
            )
        )

        every { fileDao.getAllFiles() } returns flowOf(files)
        coEvery { fileContentDao.getContent(any()) } returns "Some architectural content."

        val firstCall = repository.getDailyEchoes(limit = 1)
        assertEquals(1, firstCall.size)

        repository.markEchoReviewed("20_Projects/21_Waffle_Framework/Architecture.md")

        val secondCall = repository.getDailyEchoes(limit = 1)
        assertTrue(secondCall.isEmpty())
    }
}
