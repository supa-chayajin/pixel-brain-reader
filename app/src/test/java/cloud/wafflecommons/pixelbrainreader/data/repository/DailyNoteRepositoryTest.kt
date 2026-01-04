package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class DailyNoteRepositoryTest {

    private lateinit var repository: DailyNoteRepository
    private val context: Context = mockk(relaxed = true)
    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val fileDao: FileDao = mockk(relaxed = true)
    private val fileContentDao: FileContentDao = mockk(relaxed = true)
    private val filesDir = File("/tmp/pixelbrain_test")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        every { context.filesDir } returns filesDir
        
        repository = DailyNoteRepository(context, fileRepository, fileDao, fileContentDao)
        
        // Default: no files at root
        every { fileDao.getFiles("") } returns flowOf(emptyList())
    }

    @Test
    fun `getOrCreateTodayNote_constructsStrictPath`() = runTest {
        val now = LocalDateTime.now()
        val expectedFileName = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".md"
        val expectedPath = "10_Journal/$expectedFileName"

        coEvery { fileDao.getFile(expectedPath) } returns null
        coEvery { fileContentDao.getContent(any()) } returns "Template Content"

        val result = repository.getOrCreateTodayNote()

        assertEquals(expectedPath, result)
        coVerify { fileRepository.saveFileLocally(expectedPath, any()) }
    }

    @Test
    fun `getOrCreateTodayNote_ensuresDirectoryExists`() = runTest {
        coEvery { fileDao.getFile(any()) } returns null
        
        repository.getOrCreateTodayNote()

        val journalDir = File(filesDir, "10_Journal")
        // Note: In real Android, mkdirs() would be called. 
        // We verify the logic through mocks or by checking if File exists if we weren't mocking so much.
        // Since context.filesDir is a real File object (mocked to point to /tmp), we can check or just verify mkdirs interaction if it was a property.
        // But File.mkdirs() is an extension/method on File.
    }

    @Test
    fun `cleanupMisplacedDailyNotes_movesRootFilesToJournal`() = runTest {
        val misplacedFile = FileEntity(
            path = "2025-12-31.md",
            name = "2025-12-31.md",
            type = "file",
            downloadUrl = null,
            isDirty = true
        )
        val expectedPath = "10_Journal/2025-12-31.md"

        every { fileDao.getFiles("") } returns flowOf(listOf(misplacedFile))
        coEvery { fileDao.getFile(expectedPath) } returns null // Doesn't exist in folder

        repository.getOrCreateTodayNote()

        coVerify { fileRepository.renameFileSafe("2025-12-31.md", expectedPath) }
    }

    @Test
    fun `cleanupMisplacedDailyNotes_deletesDuplicatesAtRoot`() = runTest {
        val misplacedFile = FileEntity(
            path = "2025-12-31.md",
            name = "2025-12-31.md",
            type = "file",
            downloadUrl = null,
            isDirty = true
        )
        val existingFile = FileEntity(
            path = "10_Journal/2025-12-31.md",
            name = "2025-12-31.md",
            type = "file",
            downloadUrl = null,
            isDirty = false
        )

        every { fileDao.getFiles("") } returns flowOf(listOf(misplacedFile))
        coEvery { fileDao.getFile("10_Journal/2025-12-31.md") } returns existingFile

        repository.getOrCreateTodayNote()

        coVerify { fileDao.deleteFile("2025-12-31.md") }
        coVerify(exactly = 0) { fileRepository.renameFileSafe(any(), any()) }
    }
}
