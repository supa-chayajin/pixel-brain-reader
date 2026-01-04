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
        repository = MoodRepository(fileRepository, gson, secretManager)
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
        
        // Mock Secret Manager
        every { secretManager.getRepoInfo() } returns Pair("owner", "repo")
        coEvery { fileRepository.pushDirtyFiles("owner", "repo", any()) } returns Result.success(Unit)

        // Act
        repository.addEntry(date, MoodEntry("10:00", 3, "😐", listOf("Reading"), "Good"))
        repository.addEntry(date, MoodEntry("14:00", 5, "🤩", listOf("Coding"), "Great"))

        // Assert
        val finalData = gson.fromJson(capturedContents.last(), DailyMoodData::class.java)
        assertEquals(2, finalData.entries.size)
        // Descending: 14:00 should be first
        assertEquals("14:00", finalData.entries[0].time)
        assertEquals("10:00", finalData.entries[1].time)
        
        // Verify Sync was called
        coVerify(atLeast = 2) { fileRepository.pushDirtyFiles("owner", "repo", any()) }
    }

    @Test
    fun getDailyMood_ReturnsNull_WhenFileMissing() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 4)
        val path = "10_Journal/data/health/mood/2026-01-04.json"
        
        every { fileRepository.getFileContentFlow(path) } returns flowOf(null)

        // Act
        val result = repository.getDailyMood(date).first()

        // Assert
        assertEquals(null, result)
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

        // Mock Secret Manager to prevent unexpected calls or nullable issues
        every { secretManager.getRepoInfo() } returns Pair("owner", "repo")
        coEvery { fileRepository.pushDirtyFiles(any(), any(), any()) } returns Result.success(Unit)

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
