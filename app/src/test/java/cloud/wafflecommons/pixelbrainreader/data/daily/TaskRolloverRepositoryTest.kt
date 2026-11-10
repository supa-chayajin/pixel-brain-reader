package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskRolloverRepositoryTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val noteRepository: NoteRepository = mockk()
    private val dashboardDao: DailyDashboardDao = mockk()
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val repository = TaskRolloverRepository(noteRepository, dashboardDao, taskRepository, preferences)

    private fun path(day: LocalDate) = "10_Journal/$day.md"

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `the earlier day is the most recent one with a note, read from its burned file`() = runTest {
        coEvery { noteRepository.getNoteContent(any()) } returns null
        coEvery { dashboardDao.getDashboard(any()) } returns null
        val friday = today.minusDays(3)
        coEvery { noteRepository.getNoteContent(path(friday)) } returns
            "# $friday\n\n## 📝 Journal\n\n- [ ] ‼️ Call the bank\n- [x] Buy milk\n"

        val (day, tasks) = repository.earlierDay(today) ?: throw AssertionError("no earlier day")

        assertEquals(friday, day)
        assertEquals(listOf("Call the bank" to false, "Buy milk" to true), tasks.map { it.label to it.isDone })
        assertEquals(2, tasks.first().priority)
    }

    @Test
    fun `a board only Room holds still counts, and nothing within a week means no earlier day`() = runTest {
        coEvery { noteRepository.getNoteContent(any()) } returns null
        coEvery { dashboardDao.getDashboard(any()) } returns null
        val yesterday = today.minusDays(1)
        coEvery { dashboardDao.getDashboard(yesterday) } returns DailyDashboardEntity(date = yesterday)
        val roomTasks = listOf(DailyTaskEntity(scheduledDate = yesterday.toString(), label = "Book trains"))
        coEvery { dashboardDao.getTasksSnapshot(yesterday) } returns roomTasks

        assertEquals(yesterday to roomTasks, repository.earlierDay(today))

        coEvery { dashboardDao.getDashboard(yesterday) } returns null
        assertNull(repository.earlierDay(today))
        // A week back at most: the eighth day is never read.
        coVerify(exactly = 0) { noteRepository.getNoteContent(path(today.minusDays(TaskRollover.LOOKBACK_DAYS + 1))) }
    }

    @Test
    fun `bring adds each task to today's board without its old time and remembers it, writing no file`() = runTest {
        val tasks = listOf(RolloverTask("Call the bank", 2, "k1"), RolloverTask("Book trains", 1, "k2"))

        repository.bring(today, tasks)

        coVerify(exactly = 1) { taskRepository.addTask("Call the bank", today, null, 2) }
        coVerify(exactly = 1) { taskRepository.addTask("Book trains", today, null, 1) }
        coVerify(exactly = 1) { preferences.markRolloverHandled(listOf("k1", "k2"), today) }
    }

    @Test
    fun `dismiss only remembers, and a read-only device does neither`() = runTest {
        val task = RolloverTask("Call the bank", 1, "k1")

        repository.dismiss(today, listOf(task))
        coVerify(exactly = 1) { preferences.markRolloverHandled(listOf("k1"), today) }
        coVerify(exactly = 0) { taskRepository.addTask(any(), any(), any(), any()) }

        VaultWriteGate.readOnly = true
        repository.bring(today, listOf(task))
        repository.dismiss(today, listOf(task))
        coVerify(exactly = 0) { taskRepository.addTask(any(), any(), any(), any()) }
        coVerify(exactly = 1) { preferences.markRolloverHandled(any(), any()) }
    }
}
