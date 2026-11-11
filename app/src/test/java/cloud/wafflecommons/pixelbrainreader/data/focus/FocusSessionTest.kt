package cloud.wafflecommons.pixelbrainreader.data.focus

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FocusSessionTest {

    private val start = 1_800_000_000_000L
    private val minute = 60_000L
    private val fifty = FocusSession(start, 50)

    // --- The timeline ---

    @Test
    fun `the bar steps every five minutes from the start, and the last step is the end`() {
        assertEquals(start + 5 * minute, fifty.nextStep(start))
        assertEquals(start + 5 * minute, fifty.nextStep(start + 4 * minute))
        assertEquals(start + 10 * minute, fifty.nextStep(start + 5 * minute))
        assertEquals(fifty.endMillis, fifty.nextStep(start + 48 * minute))
        assertEquals(FocusSession(start, 23).endMillis, FocusSession(start, 23).nextStep(start + 21 * minute))
        assertEquals(0, fifty.progressPercent(start - minute))
        assertEquals(50, fifty.progressPercent(start + 25 * minute))
        assertEquals(100, fifty.progressPercent(start + 90 * minute))
        assertFalse(fifty.isOver(start + 49 * minute))
        assertTrue(fifty.isOver(fifty.endMillis))
    }

    @Test
    fun `a session kept on the phone reads back, and a damaged one is none`() {
        assertEquals(fifty, FocusSession.decode(fifty.encode()))
        assertNull(FocusSession.decode(null))
        assertNull(FocusSession.decode("garbage"))
        assertNull(FocusSession.decode("$start|0"))
        assertNull(FocusSession.decode("-5|50"))
        assertEquals(25, FocusSession.xpFor(50))
    }

    // --- The notes of a session ---

    @Test
    fun `the notes edited during a session are the ones git saw then, the excluded ones aside`() {
        val inside = start + 10 * minute
        val history = FileHistory(
            lastChanged = mapOf(
                "30_Areas/Plan.md" to inside,
                "40_Resources/Kotlin.md" to fifty.endMillis + minute, // saved just before the end, pushed after
                "40_Resources/Old.md" to start - minute,
                "40_Resources/Later.md" to fifty.endMillis + 10 * minute,
                "10_Journal/2026-11-11.md" to inside,
                "99_Private/Secret.md" to inside,
                "10_Journal/data/habits/2026.json" to inside
            )
        )
        assertEquals(listOf("40_Resources/Kotlin.md", "30_Areas/Plan.md"), FocusNotes.edited(history, fifty))
        assertEquals(listOf("30_Areas/Plan.md"), FocusNotes.edited(history, fifty) { it != "40_Resources/Kotlin.md" })
    }

    @Test
    fun `the fact reads in French, with the notes as links`() {
        assertEquals("Session de concentration de 50 min", FocusNotes.logText(50, emptyList()))
        assertEquals(
            "Session de concentration de 25 min : [[30_Areas/Plan]], [[40_Resources/Kotlin]]",
            FocusNotes.logText(25, listOf("30_Areas/Plan.md", "40_Resources/Kotlin.md"))
        )
        val many = (1..10).map { "40_Resources/Note $it.md" }
        assertTrue(FocusNotes.logText(90, many).endsWith("[[40_Resources/Note 8]] et 2 autres"))
    }

    @Test
    fun `the Live Update is ongoing, asks for promotion, and counts down to the end`() {
        val spec = FocusSpec.running(fifty, start + 10 * minute, "15:20")
        assertEquals("Focus · 50 min", spec.title)
        assertEquals("Until 15:20", spec.text)
        assertEquals(20, spec.progress)
        assertEquals(fifty.endMillis, spec.countdownTo)
        assertTrue(spec.ongoing)
        assertTrue(spec.promoted)
        assertEquals("Focus done · 3 notes edited", FocusSpec.doneTitle(3))
        assertEquals("Focus done", FocusSpec.doneTitle(0))
    }

    // --- The repository ---

    private val stored = MutableStateFlow<String?>(null)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val platform: FocusPlatform = mockk(relaxed = true)
    private val gitFileAges: GitFileAges = mockk()
    private val fileContentDao: FileContentDao = mockk()
    private val grantXp: GrantXpUseCase = mockk(relaxed = true)
    private val modesRepository: ModesRepository = mockk()
    private val missionLogRepository: MissionLogRepository = mockk(relaxed = true)
    private val repository by lazy {
        FocusRepository(preferences, platform, gitFileAges, fileContentDao, grantXp, modesRepository, missionLogRepository)
    }

    @Before
    fun setUp() {
        every { preferences.focusSession } returns stored
        coEvery { preferences.setFocusSession(any()) } answers { stored.value = firstArg() }
        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.WORK)
        coEvery { gitFileAges.history() } returns FileHistory(lastChanged = mapOf("30_Areas/Plan.md" to start + 10 * minute))
        coEvery { fileContentDao.getContent(any()) } returns "# Plan\n"
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `starting keeps the session, shows it and schedules its first step`() = runTest {
        assertEquals(fifty, repository.start(50, start))

        assertEquals(fifty.encode(), stored.value)
        verify { platform.postRunning(fifty, start) }
        verify { platform.schedule(5 * minute) }
    }

    @Test
    fun `a step moves the bar on, and the last one ends with XP and the done notification`() = runTest {
        stored.value = fifty.encode()

        repository.step(start + 5 * minute)
        verify { platform.postRunning(fifty, start + 5 * minute) }
        verify { platform.schedule(5 * minute) }

        repository.step(fifty.endMillis)
        assertNull(stored.value)
        verify { platform.cancelRunning() }
        coVerify { grantXp(25) }
        verify { platform.postDone(50, listOf("30_Areas/Plan.md"), canLog = true) }
    }

    @Test
    fun `outside Work mode the end earns XP but offers no log`() = runTest {
        stored.value = fifty.encode()
        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)

        repository.step(fifty.endMillis)

        coVerify { grantXp(25) }
        verify { platform.postDone(50, listOf("30_Areas/Plan.md"), canLog = false) }
    }

    @Test
    fun `stopping early ends quietly`() = runTest {
        stored.value = fifty.encode()

        repository.stop()

        assertNull(stored.value)
        verify { platform.cancelSchedule() }
        verify { platform.cancelRunning() }
        coVerify(exactly = 0) { grantXp(any()) }
        verify(exactly = 0) { platform.postDone(any(), any(), any()) }
    }

    @Test
    fun `log it writes a focus fact in Work mode only`() = runTest {
        repository.log(50, listOf("30_Areas/Plan.md"))
        coVerify {
            missionLogRepository.add(match {
                it.kind == LogKind.FACT && it.text == "Session de concentration de 50 min : [[30_Areas/Plan]]" && it.tags == listOf("focus")
            })
        }

        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)
        repository.log(50, emptyList())
        coVerify(exactly = 1) { missionLogRepository.add(any()) }
    }

    @Test
    fun `a read-only device starts nothing`() = runTest {
        VaultWriteGate.readOnly = true

        assertNull(repository.start(50, start))

        assertNull(stored.value)
        verify(exactly = 0) { platform.postRunning(any(), any()) }
    }
}
