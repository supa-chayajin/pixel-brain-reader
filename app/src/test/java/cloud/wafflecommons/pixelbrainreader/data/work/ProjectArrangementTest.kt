package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Projects page's pins and hides (12.7.0), and the vault file that keeps them. */
class ProjectArrangementTest {

    // Most recently active first, as ProjectRepository.projects() returns them.
    private val projects = listOf("21_Garden", "22_Recipes", "23_Atlas", "24_Kiln").map { project(it) }

    @Test
    fun `pinned first in their order, then the others by activity, hidden apart`() {
        val arranged = ProjectArrangement(pinned = listOf(root("24_Kiln"), root("22_Recipes")), hidden = listOf(root("23_Atlas")))
            .arrange(projects)

        assertEquals(listOf("24_Kiln", "22_Recipes"), arranged.pinned.map { it.name })
        assertEquals(listOf("21_Garden"), arranged.others.map { it.name })
        assertEquals(listOf("23_Atlas"), arranged.hidden.map { it.name })
        assertEquals(listOf("24_Kiln", "22_Recipes", "21_Garden"), arranged.shown.map { it.name })
    }

    @Test
    fun `nothing arranged keeps the activity order`() {
        assertEquals(projects, ProjectArrangement().arrange(projects).shown)
    }

    @Test
    fun `a root that doesn't show is kept, not shown`() {
        // A work project outside Work mode, or a folder gone: still in the file, not on the page.
        val arrangement = ProjectArrangement(pinned = listOf(root("29_Elsewhere"), root("23_Atlas")), hidden = listOf(root("30_Gone")))
        val arranged = arrangement.arrange(projects)

        assertEquals(listOf("23_Atlas"), arranged.pinned.map { it.name })
        assertTrue(arranged.hidden.isEmpty())
        assertEquals(listOf(root("29_Elsewhere"), root("23_Atlas")), arrangement.unpin(root("21_Garden")).pinned)
    }

    @Test
    fun `pinning shows, hiding unpins, never both`() {
        val hidden = ProjectArrangement().hide(root("21_Garden"))
        assertTrue(hidden.isHidden(root("21_Garden")))

        val pinned = hidden.pin(root("21_Garden"))
        assertTrue(pinned.isPinned(root("21_Garden")))
        assertFalse(pinned.isHidden(root("21_Garden")))

        val hiddenAgain = pinned.hide(root("21_Garden"))
        assertFalse(hiddenAgain.isPinned(root("21_Garden")))
        assertEquals(listOf(root("21_Garden")), hiddenAgain.hidden)

        // Twice is once.
        assertEquals(pinned, pinned.pin(root("21_Garden")))
        assertEquals(ProjectArrangement(), hiddenAgain.show(root("21_Garden")))
    }

    @Test
    fun `a hand-edited file pinning a hidden root hides it`() {
        val arranged = ProjectArrangement(pinned = listOf(root("21_Garden")), hidden = listOf(root("21_Garden"))).arrange(projects)
        assertTrue(arranged.pinned.isEmpty())
        assertEquals(listOf("21_Garden"), arranged.hidden.map { it.name })
    }

    @Test
    fun `moving a pinned project swaps it with its neighbour`() {
        val arrangement = ProjectArrangement(pinned = listOf(root("21_Garden"), root("22_Recipes"), root("23_Atlas")))
        assertEquals(
            listOf(root("21_Garden"), root("23_Atlas"), root("22_Recipes")),
            arrangement.swapPinned(root("23_Atlas"), root("22_Recipes")).pinned
        )
        assertEquals(arrangement, arrangement.swapPinned(root("23_Atlas"), root("24_Kiln")))
    }

    // --- The vault file ---

    private var stored: String? = null
    private val fileRepository: FileRepository = mockk {
        coEvery { readFile(ProjectArrangementRepository.PATH) } answers { stored }
        coEvery { saveFileLocally(ProjectArrangementRepository.PATH, any()) } answers { stored = secondArg() }
        every { moveAsideCorrupt(ProjectArrangementRepository.PATH) } answers { stored = null; File("projects.json.corrupt") }
    }
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val widgetUpdateManager: WidgetUpdateManager = mockk(relaxed = true)
    private val repository = ProjectArrangementRepository(fileRepository, pushScheduler, widgetUpdateManager)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `no file is nothing arranged, and reading writes nothing`() = runTest {
        assertEquals(ProjectArrangement(), repository.read())
        assertNull(stored)
    }

    @Test
    fun `a change starts from the file on disk and is pushed`() = runTest {
        stored = """{ "pinned": [ "${root("22_Recipes")}" ] }"""

        val saved = repository.update { it.pin(root("21_Garden")) }

        assertEquals(listOf(root("22_Recipes"), root("21_Garden")), saved?.pinned)
        val file = json.parseToJsonElement(stored!!).jsonObject
        assertEquals(setOf("pinned"), file.keys)
        assertEquals(saved, repository.read())
        verify { pushScheduler.requestPush() }
        verify { widgetUpdateManager.triggerUpdate() }
    }

    @Test
    fun `a change that changes nothing writes nothing`() = runTest {
        stored = """{ "hidden": [ "${root("23_Atlas")}" ] }"""

        repository.update { it.hide(root("23_Atlas")) }

        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
        verify(exactly = 0) { pushScheduler.requestPush() }
    }

    @Test
    fun `an unreadable file is moved aside, never overwritten`() = runTest {
        stored = "{ pinned: [ oops"

        assertEquals(ProjectArrangement(), repository.read())
        val saved = repository.update { it.hide(root("23_Atlas")) }

        verify { fileRepository.moveAsideCorrupt(ProjectArrangementRepository.PATH) }
        assertEquals(listOf(root("23_Atlas")), saved?.hidden)
    }

    @Test
    fun `an unreadable file that can't be moved aside stops the change`() = runTest {
        stored = "{ pinned: [ oops"
        every { fileRepository.moveAsideCorrupt(ProjectArrangementRepository.PATH) } returns null

        assertNull(repository.update { it.hide(root("23_Atlas")) })
        assertEquals("{ pinned: [ oops", stored)
    }

    @Test
    fun `a read-only device changes nothing`() = runTest {
        stored = """{ "pinned": [ "${root("22_Recipes")}" ] }"""
        VaultWriteGate.readOnly = true

        assertNull(repository.update { it.hide(root("22_Recipes")) })

        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
        assertEquals(listOf(root("22_Recipes")), repository.read().pinned)
    }

    private fun root(name: String) = "20_Projects/$name"

    private fun project(name: String) = ProjectSummary(
        root = root(name),
        title = name,
        status = null,
        lastActivity = null,
        days = emptyList(),
        actions = emptyList(),
        claudeQueue = emptyList(),
        keyNotes = emptyList(),
        recentNotes = emptyList(),
        linkedFrom = emptyList(),
        todoNote = null
    )
}
