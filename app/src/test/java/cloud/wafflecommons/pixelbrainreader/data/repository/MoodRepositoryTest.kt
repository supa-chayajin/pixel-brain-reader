package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.MoodDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MoodRepositoryTest {

    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val moodDao: MoodDao = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val widgetUpdateManager: WidgetUpdateManager = mockk(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private lateinit var repository: MoodRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0

        repository = MoodRepository(
            fileRepository = fileRepository,
            moodDao = moodDao,
            database = database,
            pushScheduler = pushScheduler,
            widgetUpdateManager = widgetUpdateManager
        )
    }

    @Test
    fun addEntry_CreatesNewFile_WhenMissing() = runTest {
        val date = LocalDate.of(2026, 1, 5)
        val path = "10_Journal/data/health/mood/2026-01-05.json"

        coEvery { fileRepository.readFile(path) } returns null

        val capturedContentSlot = slot<String>()
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContentSlot)) } just Runs

        val entry = MoodEntry("09:00", 3, "😐", listOf("Reading"))
        repository.addEntry(date, entry)

        coVerify { fileRepository.saveFileLocally(path, any()) }
        coVerify { moodDao.insertMood(any()) }

        val savedData = json.decodeFromString<DailyMoodDataDto>(capturedContentSlot.captured)
        assertEquals(1, savedData.entries.size)
        assertEquals("09:00", savedData.entries[0].time)
        assertEquals(date.toString(), savedData.date)
    }

    @Test
    fun addEntry_AppendsEntry_WhenFileExists() = runTest {
        val date = LocalDate.of(2026, 1, 5)
        val path = "10_Journal/data/health/mood/2026-01-05.json"

        val existingEntry = MoodEntryDto("10:00", 4, "🙂", listOf("Walk"))
        val existingData = DailyMoodDataDto(date.toString(), listOf(existingEntry), MoodSummaryDto(4.0, "🙂"))
        coEvery { fileRepository.readFile(path) } returns json.encodeToString(existingData)

        val capturedContentSlot = slot<String>()
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContentSlot)) } just Runs

        val newEntry = MoodEntry("09:00", 3, "😐", listOf("Work"))
        repository.addEntry(date, newEntry)

        val savedData = json.decodeFromString<DailyMoodDataDto>(capturedContentSlot.captured)
        assertEquals(2, savedData.entries.size)
        assertEquals("10:00", savedData.entries[0].time)
        assertEquals("09:00", savedData.entries[1].time)
    }

    @Test
    fun getDailyMood_ReturnsNull_WhenNoEntitiesInRoom() = runTest {
        val date = LocalDate.of(2026, 1, 6)
        every { moodDao.getMood(date.toString()) } returns flowOf(emptyList<MoodEntity>())

        val result = repository.getDailyMood(date).first()
        assertEquals(null, result)
    }
}
