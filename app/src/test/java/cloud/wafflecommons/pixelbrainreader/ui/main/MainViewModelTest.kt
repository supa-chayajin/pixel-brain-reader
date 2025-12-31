package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: MainViewModel
    
    // Mocks
    private val repository: FileRepository = mockk(relaxed = true)
    private val dailyNoteRepository: DailyNoteRepository = mockk(relaxed = true)
    private val templateRepository: TemplateRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val geminiRagManager: GeminiRagManager = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        
        // Default Stubs
        every { userPrefs.listPaneWidth } returns flowOf(360f)
        every { secretManager.getRepoInfo() } returns Pair("user", "repo")
        coEvery { repository.getFiles(any()) } returns flowOf(emptyList()) 
        
        viewModel = MainViewModel(repository, dailyNoteRepository, templateRepository, secretManager, userPrefs, geminiRagManager)
    }

    // --- 1. Initialization & Navigation ---

    @Test
    fun `initial load fetches root files`() = runTest {
        // Assert initial call
        advanceUntilIdle()
        verify { repository.getFiles("") }
        assertEquals("", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `loadFolder updates currentPath and observes DB`() = runTest {
        val targetPath = "docs/guides"
        viewModel.loadFolder(targetPath)
        advanceUntilIdle()

        assertEquals(targetPath, viewModel.uiState.value.currentPath)
        verify { repository.getFiles(targetPath) }
    }

    @Test
    fun `mapsBack handles navigation logic`() = runTest {
        // CASE: Root -> False (Exit)
        viewModel.loadFolder("")
        assertFalse("Should return false at root", viewModel.mapsBack())

        // CASE: Subfolder -> True (Navigate Up)
        viewModel.loadFolder("docs/nested")
        assertTrue("Should return true in subfolder", viewModel.mapsBack())
        assertEquals("docs", viewModel.uiState.value.currentPath)
    }

    // --- 2. File Operations ---

    @Test
    fun `renameFile adds extension for files`() = runTest {
        // Setup State
        val fileDto = GithubFileDto(
            name = "oldName.md", 
            path = "path/oldName.md", 
            type = "file", 
            downloadUrl = null, 
            sha = null,
            lastModified = 0L
        )
        // Mock Finding the file in current list (for path resolution)
        every { repository.getFiles("") } returns flowOf(
            listOf(FileEntity("path/oldName.md", "oldName.md", "file", null))
        )
        // Refresh Files State
        viewModel.loadFolder("") // Update UI State
        advanceUntilIdle() // Wait for ensure files are loaded in State
        
        coEvery { repository.renameAndSync(any(), any(), any(), any()) } returns Result.success(Unit)

        // Action: Rename "newName" (missing extension)
        viewModel.renameFile("newName", fileDto)
        advanceUntilIdle()

        // Verify
        coVerify { 
            repository.renameAndSync(
                "path/oldName.md", 
                "path/newName.md", // Extension added
                "user", 
                "repo"
            ) 
        }
        assertEquals("Renamed successfully", viewModel.uiState.value.userMessage)
    }

    @Test
    fun `renameFile does NOT add extension for directories`() = runTest {
        val dirDto = GithubFileDto(
            name = "oldFolder", 
            path = "path/oldFolder", 
            type = "dir", 
            downloadUrl = null, 
            sha = null,
            lastModified = 0L
        )
        every { repository.getFiles("") } returns flowOf(
             listOf(FileEntity("path/oldFolder", "oldFolder", "dir", null))
        )
        viewModel.loadFolder("") // Update State
        advanceUntilIdle() // Wait for ensure files are loaded in State
        
        coEvery { repository.renameAndSync(any(), any(), any(), any()) } returns Result.success(Unit)

        viewModel.renameFile("newFolder", dirDto) // No extension
        advanceUntilIdle()

        coVerify { 
            repository.renameAndSync(
                "path/oldFolder", 
                "path/newFolder", // NO extension added
                "user", 
                "repo"
            ) 
        }
    }

    @Test
    fun `createNewFile saves locally and sets edit mode`() = runTest {
        viewModel.loadFolder("docs")
        
        viewModel.createNewFile()
        advanceUntilIdle()
        
        // Assertions
        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertTrue(state.hasUnsavedChanges)
        assertEquals("", state.unsavedContent)
        assertTrue(state.selectedFileName?.startsWith("Untitled_") == true)
        
        // Check Save Called
        coVerify { repository.saveFileLocally(match { it.startsWith("docs/Untitled_") }, "") }
    }

    @Test
    fun `saveFile persists and triggers push`() = runTest {
        // Setup State via create (simplest way to get valid state)
        viewModel.createNewFile()
        advanceUntilIdle()
        val createdFile = viewModel.uiState.value.selectedFileName ?: ""
        
        // Edit Content
        val newContent = "Updated content"
        viewModel.onContentChanged(newContent)
        
        coEvery { repository.pushDirtyFiles(any(), any()) } returns Result.success(Unit)

        // Action
        viewModel.saveFile()
        advanceUntilIdle()

        // Verify
        // 1. Local Save
        coVerify { repository.saveFileLocally(match { it.endsWith(createdFile) }, newContent) }
        
        // 2. State Reset 
        val state = viewModel.uiState.value
        assertFalse("Should leave edit mode", state.isEditing)
        assertFalse("Should clear dirty flag", state.hasUnsavedChanges)
        assertEquals(newContent, state.selectedFileContent)
        
        // 3. Push Triggered
        coVerify { repository.pushDirtyFiles("user", "repo") }
    }

    // --- 3. Synchronization & State ---

    @Test
    fun `refreshCurrentFolder syncs and updates refreshing state`() = runTest {
        coEvery { repository.syncRepository(any(), any()) } returns Result.success(Unit)

        viewModel.refreshCurrentFolder()
        
        // Since runTest skips delays, we can't easily assert the "true" state during execution 
        // without complex coroutine management, but we can verify the CALL happened 
        coEvery { repository.syncRepository(any(), any()) } returns Result.success(Unit)

        viewModel.refreshCurrentFolder()
        advanceUntilIdle() // Allow coroutine to run
        
        coVerify { repository.syncRepository("user", "repo") }
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `refreshCurrentFolder sets error state on failure`() = runTest {
        val errorMsg = "Network Timeout"
        coEvery { repository.syncRepository(any(), any()) } returns Result.failure(Exception(errorMsg))

        viewModel.refreshCurrentFolder()
        advanceUntilIdle()

        assertEquals("Sync Failed: $errorMsg", viewModel.uiState.value.error)
    }

    // --- 4. Import Logic ---

    @Test
    fun `confirmImport saves file and triggers sync`() = runTest {
        val fileName = "imported.md"
        val folder = "imports"
        val content = "# Imported Content"
        
        coEvery { repository.pushDirtyFiles(any(), any()) } returns Result.success(Unit)

        viewModel.confirmImport(fileName, folder, content)
        advanceUntilIdle()

        // Verify Local Save
        coVerify { repository.saveFileLocally("imports/imported.md", content) }
        
        // Verify Push
        coVerify { repository.pushDirtyFiles("user", "repo") }
        
    }

    @Test
    fun `search filters files correctly`() = runTest {
        // ... (Existing test replaced/updated to reflect new global logic logic if needed, but I'll add new one)
        // Actually, the previous test mocked `repository.getFiles()`.
        // The NEW logic calls `repository.searchFiles()` when query is present.
        // So I must update the mocks in the existing test or standard setup.
        
        // Mock Data
        val file1 = FileEntity("path/to/File1.md", "File1.md", "file", null)
        val file2 = FileEntity("path/to/File2.md", "File2.md", "file", null)
        val allFiles = listOf(file1, file2)
        
        // When query is empty -> calls getFiles
        coEvery { repository.getFiles("") } returns flowOf(allFiles)
        
        // When query is "1" -> calls searchFiles
        coEvery { repository.searchFiles("1") } returns flowOf(listOf(file1))
        
        viewModel = MainViewModel(repository, dailyNoteRepository, templateRepository, secretManager, userPrefs, geminiRagManager)
        advanceUntilIdle() // Initial load
        
        // Assert Initial
        assertEquals(2, viewModel.uiState.value.files.size)
        
        // Action: Search
        viewModel.onSearchQueryChanged("1")
        advanceUntilIdle()
        
        // Assert: Filtered
        assertEquals(1, viewModel.uiState.value.files.size)
        assertEquals("File1.md", viewModel.uiState.value.files.first().name)
        
        // Action: Clear
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()
        
        // Assert: Restored
        assertEquals(2, viewModel.uiState.value.files.size)
    }

    @Test
    fun `search deep search criteria (content and path)`() = runTest {
         // Scenario:
         // File A: Name="Invoice.md", Content="Plumbing"
         // File B: Name="Notes.md", Content="Meeting with plumber"
         // Folder C: Path="Work/Plumbing/Specs.md"
         
         // In a real DB, the SQL query does the work.
         // In Unit Test, we mock the Repository return value.
         // So we aren't testing the SQL logic (DaoTest would do that), but we are testing that VM calls the right method and updates state.
         
         val fileA = FileEntity("Invoice.md", "Invoice.md", "file", null)
         val fileB = FileEntity("Notes.md", "Notes.md", "file", null)
         val fileC = FileEntity("Work/Plumbing/Specs.md", "Specs.md", "file", null)
         
         // Mock: Search for "plumb" returns all 3 (simulating DB hit)
         coEvery { repository.searchFiles("plumb") } returns flowOf(listOf(fileA, fileB, fileC))
         
         // Mock: Search for "banana" returns empty
         coEvery { repository.searchFiles("banana") } returns flowOf(emptyList())
         
         // Initial Load (Empty query)
         coEvery { repository.getFiles("") } returns flowOf(emptyList()) // Just needed for init
        viewModel = MainViewModel(repository, dailyNoteRepository, templateRepository, secretManager, userPrefs, geminiRagManager)
         advanceUntilIdle()

         // 1. Search "plumb"
         viewModel.onSearchQueryChanged("plumb")
         advanceUntilIdle()
         
         assertEquals(3, viewModel.uiState.value.files.size)
         
         // 2. Search "banana"
         viewModel.onSearchQueryChanged("banana")
         advanceUntilIdle()
         
         assertEquals(0, viewModel.uiState.value.files.size)
    }
}
