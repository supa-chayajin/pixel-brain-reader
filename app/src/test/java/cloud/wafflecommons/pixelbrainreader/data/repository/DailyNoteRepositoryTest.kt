package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.GratitudeDao
import cloud.wafflecommons.pixelbrainreader.data.usecase.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class DailyNoteRepositoryTest {

    private lateinit var repository: DailyNoteRepository
    private val context: Context = mockk(relaxed = true)
    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val fileDao: FileDao = mockk(relaxed = true)
    private val fileContentDao: FileContentDao = mockk(relaxed = true)
    private val gratitudeDao: GratitudeDao = mockk(relaxed = true)
    private val dailyDashboardDao: DailyDashboardDao = mockk(relaxed = true)
    private val grantXpUseCase: GrantXpUseCase = mockk(relaxed = true)
    private val safeFileProvider: SafeFileProvider = mockk(relaxed = true)
    private val filesDir = File("/tmp/pixelbrain_test")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        every { context.filesDir } returns filesDir
        every { safeFileProvider.getSafeFile(any()) } answers { File(filesDir, firstArg<String>()) }
        every { fileDao.getFiles("") } returns flowOf(emptyList())

        repository = DailyNoteRepository(
            context = context,
            fileRepository = fileRepository,
            fileDao = fileDao,
            fileContentDao = fileContentDao,
            gratitudeDao = gratitudeDao,
            dailyDashboardDao = dailyDashboardDao,
            grantXpUseCase = grantXpUseCase,
            safeFileProvider = safeFileProvider
        )
    }

    @Test
    fun `getOrCreateTodayNote_constructsStrictPath`() = runTest {
        val today = LocalDate.now()
        val expectedFileName = today.format(DateTimeFormatter.ISO_DATE) + ".md"
        val expectedPath = "10_Journal/$expectedFileName"

        coEvery { fileDao.getFile(expectedPath) } returns null
        coEvery { fileRepository.readFile(any()) } returns "Template Content"

        val result = repository.getOrCreateTodayNote()

        assertEquals(expectedPath, result)
        coVerify { fileRepository.saveFileLocally(expectedPath, any()) }
    }

    @Test
    fun `addGratitude_insertsDashboardAndGratitudeAndGrantsXp`() = runTest {
        val date = LocalDate.of(2026, 2, 14)

        repository.addGratitude(date, "Grateful for good coffee")

        coVerify { dailyDashboardDao.insertDashboard(match { it.date == date }) }
        coVerify { gratitudeDao.insertGratitude(match { it.date == "2026-02-14" && it.content == "Grateful for good coffee" }) }
        coVerify { grantXpUseCase(50) }
    }
}
