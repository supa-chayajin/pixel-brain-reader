package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** A timeline entry is one line of the burned daily note, so Room must never hold a multi-line one. */
class DailyDashboardRepositoryTimelineTest {

    private val dashboardDao: DailyDashboardDao = mockk(relaxed = true)
    private val repository = DailyDashboardRepository(
        dashboardDao = dashboardDao,
        scratchDao = mockk(relaxed = true),
        fileRepository = mockk(relaxed = true),
        briefingGenerator = mockk(relaxed = true),
        weatherRepository = mockk(relaxed = true),
        gratitudeDao = mockk(relaxed = true),
        widgetUpdateManager = mockk(relaxed = true),
        pushScheduler = mockk(relaxed = true)
    )
    private val date = LocalDate.of(2026, 10, 17)

    @Test
    fun `adding a multi-line entry stores it as one line`() = runBlocking {
        coEvery { dashboardDao.getDashboard(any()) } returns null

        repository.addTimelineEntry(date, "Standup\n- point A\n\n- point B", LocalTime.of(9, 0))

        coVerify { dashboardDao.insertTimelineEntry(match { it.content == "Standup · - point A · - point B" }) }
    }

    @Test
    fun `editing an entry into several lines stores it as one line`() = runBlocking {
        val entry = TimelineEntryEntity(date = date, time = LocalTime.of(10, 0), content = "line one\nline two")

        repository.updateTimelineEntry(entry)

        coVerify { dashboardDao.insertTimelineEntry(match { it.content == "line one · line two" && it.id == entry.id }) }
    }
}
