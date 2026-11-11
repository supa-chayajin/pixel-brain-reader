package cloud.wafflecommons.pixelbrainreader.ui.daily

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.review.ReviewWeek
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyFacts
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyReviewFormat
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val today = MutableStateFlow(LocalDate.now())
    private val foregroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val noteRepository: NoteRepository = mockk()
    private var viewModel: WeeklyPlanViewModel? = null

    private val week get() = ReviewWeek.lastDue(LocalDateTime.now())
    private val untouched get() = WeeklyReviewFormat.render(week, WeeklyFacts())
    private val planned get() = untouched.replace(
        WeeklyReviewFormat.PLAN_HEADING + "\n\n-\n",
        WeeklyReviewFormat.PLAN_HEADING + "\n\n- Finir la refonte\n"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel?.cancelAndAwait(testDispatcher.scheduler)
        Dispatchers.resetMain()
        VaultWriteGate.readOnly = false
    }

    private fun create(): WeeklyPlanViewModel {
        val clock = mockk<AppClock>()
        every { clock.today } returns today
        every { clock.foregroundEvents } returns foregroundEvents
        return WeeklyPlanViewModel(noteRepository, clock).also { viewModel = it }
    }

    @Test
    fun `a review waiting for its plan shows the banner on today's page`() = runTest(testDispatcher) {
        coEvery { noteRepository.getNoteContent(week.path) } returns untouched

        val vm = create()
        advanceUntilIdle()

        assertEquals(WeeklyPlanPrompt(LocalDate.now(), week), vm.prompt.value)
    }

    @Test
    fun `the banner clears once the plan is written and Daily shows again`() = runTest(testDispatcher) {
        coEvery { noteRepository.getNoteContent(week.path) } returns untouched
        val vm = create()
        advanceUntilIdle()

        coEvery { noteRepository.getNoteContent(week.path) } returns planned
        vm.reload()
        advanceUntilIdle()

        assertNull(vm.prompt.value)
    }

    @Test
    fun `no review means no banner`() = runTest(testDispatcher) {
        coEvery { noteRepository.getNoteContent(any()) } returns null

        val vm = create()
        advanceUntilIdle()

        assertNull(vm.prompt.value)
    }

    @Test
    fun `a read-only device never reads nor shows it`() = runTest(testDispatcher) {
        VaultWriteGate.readOnly = true
        coEvery { noteRepository.getNoteContent(any()) } returns untouched

        val vm = create()
        advanceUntilIdle()

        assertNull(vm.prompt.value)
        coVerify(exactly = 0) { noteRepository.getNoteContent(any()) }
    }
}
