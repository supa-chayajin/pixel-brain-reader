package cloud.wafflecommons.pixelbrainreader.data.repository

import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MoodRepositoryTest {

    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val gson = Gson()
    private lateinit var repository: MoodRepository

    @Before
    fun setup() {
        repository = MoodRepository(fileRepository, gson)
    }

    @Test
    fun addEntry_SortedByTime() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 4)
        val path = "10_Journal/data/health/mood/2026-01-04.json"
        
        val capturedContents = mutableListOf<String>()
        coEvery { fileRepository.createLocalFolder(any()) } just Runs
        every { fileRepository.getFileContentFlow(path) } answers {
            flowOf(capturedContents.lastOrNull())
        }
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContents)) } just Runs

        val entry1 = MoodEntry("14:00", 4, "🙂", emptyList())
        val entry2 = MoodEntry("10:00", 5, "🤩", emptyList())

        // Act
        repository.addEntry(date, entry1)
        repository.addEntry(date, entry2)

        // Assert
        coVerify(exactly = 2) { fileRepository.saveFileLocally(path, any()) }
        
        val finalData = gson.fromJson(capturedContents.last(), DailyMoodData::class.java)
        assertEquals("10:00", finalData.entries[0].time)
        assertEquals("14:00", finalData.entries[1].time)
    }

    @Test
    fun addEntry_UpdatesSummary() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 4)
        val path = "10_Journal/data/health/mood/2026-01-04.json"
        
        val capturedContents = mutableListOf<String>()
        coEvery { fileRepository.createLocalFolder(any()) } just Runs
        every { fileRepository.getFileContentFlow(path) } answers {
            flowOf(capturedContents.lastOrNull())
        }
        coEvery { fileRepository.saveFileLocally(path, capture(capturedContents)) } just Runs

        val entry1 = MoodEntry("10:00", 2, "😞", emptyList())
        val entry2 = MoodEntry("12:00", 4, "🙂", emptyList())

        // Act
        repository.addEntry(date, entry1)
        repository.addEntry(date, entry2)

        // Assert: Average of 2 and 4 is 3.0
        val finalData = gson.fromJson(capturedContents.last(), DailyMoodData::class.java)
        assertEquals(3.0, finalData.summary.averageScore, 0.1)
        assertEquals("😐", finalData.summary.mainEmoji)
    }
}
