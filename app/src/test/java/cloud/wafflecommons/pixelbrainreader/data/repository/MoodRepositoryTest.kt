package cloud.wafflecommons.pixelbrainreader.data.repository

import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MoodRepositoryTest {

    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager = mockk(relaxed = true)
    private val gson = Gson()
    private lateinit var repository: MoodRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        
        repository = MoodRepository(fileRepository, gson, secretManager)
    }

    @Test
    fun addEntry_CreatesNewFile_WhenMissing() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 5)
        val path = "10_Journal/data/health/mood/2026-01-05.json"
        
        // Mock readFile returning failure/null (simulated by empty flow or null content)
        // The repository uses .first() on the flow.
        every { fileRepository.getFileContentFlow(path) } returns flowOf(null)
        
        val capturedContentSlot = slot<String>()
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContentSlot)) } just Runs
        
        // Act
        val entry = MoodEntry("09:00", 3, "😐", listOf("Reading"))
        repository.addEntry(date, entry)

        // Assert
        verify { fileRepository.getFileContentFlow(path) }
        coVerify { fileRepository.saveFileLocally(path, any()) }
        
        val savedData = gson.fromJson(capturedContentSlot.captured, DailyMoodData::class.java)
        assertEquals(1, savedData.entries.size)
        assertEquals("09:00", savedData.entries[0].time)
        assertEquals(date.toString(), savedData.date)
    }

    @Test
    fun addEntry_AppendsAndSorts_WhenFileExists() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 5)
        val path = "10_Journal/data/health/mood/2026-01-05.json"
        
        // Existing data: One entry at 10:00
        val existingEntry = MoodEntry("10:00", 4, "🙂", listOf("Walk"))
        val existingData = DailyMoodData(date.toString(), listOf(existingEntry), MoodSummary(4.0, "🙂"))
        val existingJson = gson.toJson(existingData)
        
        every { fileRepository.getFileContentFlow(path) } returns flowOf(existingJson)
        
        val capturedContentSlot = slot<String>()
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContentSlot)) } just Runs

        // Act
        // New entry at 09:00 (Should be AFTER 10:00 if descending)
        val newEntry = MoodEntry("09:00", 3, "😐", listOf("Work"))
        repository.addEntry(date, newEntry)

        // Assert
        val savedData = gson.fromJson(capturedContentSlot.captured, DailyMoodData::class.java)
        assertEquals(2, savedData.entries.size)
        
        // Expected Order: Descending (Newest First)
        // 10:00 is > 09:00, so 10:00 should be index 0
        assertEquals("10:00", savedData.entries[0].time)
        assertEquals("09:00", savedData.entries[1].time)
    }

    @Test
    fun getDailyMood_ReturnsNull_WhenFileMissing() = runTest {
         // Arrange
        val date = LocalDate.of(2026, 1, 6)
        val path = "10_Journal/data/health/mood/2026-01-06.json"
        
        every { fileRepository.getFileContentFlow(any()) } returns flowOf(null)

        // Act
        val result = repository.getDailyMood(date).first()

        // Assert
        assertEquals(null, result)
    }
}
