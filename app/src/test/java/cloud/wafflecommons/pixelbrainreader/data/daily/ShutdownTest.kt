package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ShutdownTest {

    private val wednesday = LocalDate.of(2026, 11, 11)
    private val config = ModesConfig(scheduleEnabled = true, workDays = listOf(1, 2, 3, 4, 5), workEnd = "18:00", outsideHoursMode = ContextMode.PERSONAL)

    private fun task(label: String, done: Boolean = false, google: String? = null, priority: Int = 1) =
        DailyTaskEntity(scheduledDate = wednesday.toString(), label = label, isDone = done, googleTaskId = google, priority = priority)

    // --- When ---

    @Test
    fun `the banner shows from half an hour before work ends, on work days, until the day is closed`() {
        assertFalse(Shutdown.isDue(config, wednesday.atTime(17, 29), null))
        assertTrue(Shutdown.isDue(config, wednesday.atTime(17, 30), null))
        assertTrue(Shutdown.isDue(config, wednesday.atTime(23, 59), null))
        assertFalse(Shutdown.isDue(config, wednesday.atTime(19, 0), wednesday))
        assertTrue(Shutdown.isDue(config, wednesday.atTime(19, 0), wednesday.minusDays(1)))
        assertFalse(Shutdown.isDue(config, LocalDate.of(2026, 11, 14).atTime(18, 0), null))
        // The schedule switched off still has its days and hours.
        assertTrue(Shutdown.isDue(config.copy(scheduleEnabled = false), wednesday.atTime(17, 45), null))
        assertTrue(Shutdown.isDue(config.copy(workEnd = "16:30"), wednesday.atTime(16, 0), null))
    }

    // --- What ---

    @Test
    fun `only today's open local tasks the owner hasn't handled are asked about`() {
        val tasks = listOf(task("Call the bank"), task("Buy milk", done = true), task("Google one", google = "g1"), task("Book trains"))
        val handled = setOf(Shutdown.handledKey(wednesday, " book  TRAINS "))
        assertEquals(listOf("Call the bank"), Shutdown.openTasks(tasks, wednesday, handled).map { it.label })
        assertEquals(TaskRollover.key(wednesday, "Call the bank"), Shutdown.handledKey(wednesday, "Call the bank"))
    }

    @Test
    fun `the someday note is created once, then only appended`() {
        val first = Shutdown.withSomeday(null, "Call the bank", wednesday)
        assertTrue(first.startsWith("---\n"))
        assertTrue(first.contains("\n# Un jour\n"))
        assertTrue(first.endsWith("- [ ] Call the bank (2026-11-11)\n"))

        val second = Shutdown.withSomeday(first, "Repaint\nthe fence", wednesday.plusDays(1))
        assertTrue(second.startsWith(first.trimEnd('\n')))
        assertTrue(second.endsWith("- [ ] Call the bank (2026-11-11)\n- [ ] Repaint the fence (2026-11-12)\n"))

        assertEquals(second, Shutdown.withSomeday(second, "  call the BANK ", wednesday.plusDays(2)))
        val done = second.replace("- [ ] Call the bank", "- [x] Call the bank")
        assertTrue(Shutdown.withSomeday(done, "Call the bank", wednesday.plusDays(2)).endsWith("- [ ] Call the bank (2026-11-13)\n"))
    }

    @Test
    fun `the inbox count leaves the someday note aside`() {
        assertEquals(2, Shutdown.inboxCount(listOf("00_Inbox/Voice note.md", Shutdown.SOMEDAY_PATH, "00_Inbox/Clip.md", "00_Inbox/image.png")))
    }

    // --- The repository ---

    private val dashboardDao: DailyDashboardDao = mockk()
    private val dashboardRepository: DailyDashboardRepository = mockk(relaxed = true)
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val fileContentDao: FileContentDao = mockk()
    private val missionLogRepository: MissionLogRepository = mockk(relaxed = true)
    private val modesRepository: ModesRepository = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val repository = ShutdownRepository(
        dashboardDao, dashboardRepository, taskRepository, fileRepository, fileContentDao,
        missionLogRepository, modesRepository, preferences, pushScheduler
    )

    @Before
    fun setUp() {
        every { modesRepository.config } returns MutableStateFlow(config)
        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.WORK)
        every { preferences.shutdownClosedDay } returns flowOf(null)
        every { preferences.rolloverHandled } returns flowOf(emptySet())
        coEvery { dashboardDao.getTasksSnapshot(any()) } returns listOf(task("Call the bank"), task("Buy milk", done = true))
        coEvery { fileContentDao.pathsUnder(any()) } returns listOf("00_Inbox/Voice note.md")
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `the banner state lists the open tasks and the inbox, and the win only in Work mode`() = runTest {
        val state = repository.state(wednesday.atTime(17, 40)) ?: throw AssertionError("not due")
        assertEquals(listOf("Call the bank"), state.tasks.map { it.label })
        assertEquals(1, state.inboxCount)
        assertTrue(state.canLogWin)

        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)
        assertFalse(repository.state(wednesday.atTime(18, 5))!!.canLogWin)
        assertNull(repository.state(wednesday.atTime(16, 0)))
    }

    @Test
    fun `tomorrow puts the task on tomorrow's board and keeps the rollover away`() = runTest {
        repository.toTomorrow(wednesday, task("Call the bank", priority = 2))

        coVerify { taskRepository.addTask("Call the bank", wednesday.plusDays(1), null, 2) }
        coVerify { preferences.markRolloverHandled(listOf(Shutdown.handledKey(wednesday, "Call the bank")), wednesday) }
    }

    @Test
    fun `someday appends to the note read from disk, and pushes`() = runTest {
        coEvery { fileRepository.readFile(Shutdown.SOMEDAY_PATH) } returns null
        val saved = slot<String>()
        coEvery { fileRepository.saveFileLocally(Shutdown.SOMEDAY_PATH, capture(saved)) } returns Unit

        repository.toSomeday(wednesday, task("Call the bank"))

        assertTrue(saved.captured.endsWith("- [ ] Call the bank (2026-11-11)\n"))
        coVerify { pushScheduler.requestPush() }
        coVerify { preferences.markRolloverHandled(listOf(Shutdown.handledKey(wednesday, "Call the bank")), wednesday) }
    }

    @Test
    fun `let it go only keeps the rollover away`() = runTest {
        repository.letGo(wednesday, task("Call the bank"))

        coVerify { preferences.markRolloverHandled(listOf(Shutdown.handledKey(wednesday, "Call the bank")), wednesday) }
        coVerify(exactly = 0) { taskRepository.addTask(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
    }

    @Test
    fun `the win is a mission-log fact tagged victoire, and a blank one is nothing`() = runTest {
        val at = wednesday.atTime(17, 50)
        repository.logWin("  Shipped the weekly review \n", at)
        coVerify {
            missionLogRepository.add(match { it.kind == LogKind.FACT && it.text == "Shipped the weekly review" && it.tags == listOf("victoire") && it.at == at })
        }
        repository.logWin("   ", at)
        coVerify(exactly = 1) { missionLogRepository.add(any()) }
    }

    @Test
    fun `closing the day burns the note first, then offers the evening mode unless it is on`() = runTest {
        assertEquals(ContextMode.PERSONAL, repository.closeDay(wednesday))
        coVerifyOrder {
            dashboardRepository.burnToDisk(wednesday)
            preferences.setShutdownClosedDay(wednesday)
        }

        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)
        assertNull(repository.closeDay(wednesday))
    }

    @Test
    fun `a read-only device neither shows nor writes anything`() = runTest {
        VaultWriteGate.readOnly = true
        assertNull(repository.state(wednesday.atTime(17, 40)))
        repository.toTomorrow(wednesday, task("Call the bank"))
        repository.toSomeday(wednesday, task("Call the bank"))
        repository.logWin("A win")
        assertNull(repository.closeDay(wednesday))
        coVerify(exactly = 0) { taskRepository.addTask(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
        coVerify(exactly = 0) { missionLogRepository.add(any()) }
        coVerify(exactly = 0) { dashboardRepository.burnToDisk(any()) }
    }
}
