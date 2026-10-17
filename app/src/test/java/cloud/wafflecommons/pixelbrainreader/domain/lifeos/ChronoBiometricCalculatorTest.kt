package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChronoBiometricCalculatorTest {

    @Test
    fun calculate_emptyMetrics_returnsEmptyReport() {
        val report = ChronoBiometricCalculator.calculate(emptyList(), emptyList())
        assertEquals(0, report.analyzedDays)
        assertEquals(0L, report.avgSleepMinutes)
        assertEquals(0L, report.avgSteps)
        assertTrue(report.insights.isEmpty())
    }

    @Test
    fun calculate_correlatesGoodSleepWithHigherTaskCompletion() {
        val date1 = "2026-10-15" // 8h sleep -> 2/2 tasks done
        val date2 = "2026-10-16" // 5h sleep -> 0/2 tasks done

        val metrics = listOf(
            DailyHealthMetrics(date = date1, steps = 8500, sleepDurationMinutes = 480, averageHeartRate = 62),
            DailyHealthMetrics(date = date2, steps = 4000, sleepDurationMinutes = 300, averageHeartRate = 68)
        )

        val tasks = listOf(
            DailyTaskEntity(id = "1", scheduledDate = date1, label = "Task 1", isDone = true),
            DailyTaskEntity(id = "2", scheduledDate = date1, label = "Task 2", isDone = true),
            DailyTaskEntity(id = "3", scheduledDate = date2, label = "Task 3", isDone = false),
            DailyTaskEntity(id = "4", scheduledDate = date2, label = "Task 4", isDone = false)
        )

        val report = ChronoBiometricCalculator.calculate(metrics, tasks)

        assertEquals(2, report.analyzedDays)
        assertEquals(390L, report.avgSleepMinutes)
        assertEquals(6250L, report.avgSteps)

        val sleepInsight = report.insights.find { it.iconType == "sleep" }
        assertNotNull(sleepInsight)
        assertTrue(sleepInsight!!.description.contains(">7h de sommeil"))

        val stepsInsight = report.insights.find { it.iconType == "steps" }
        assertNotNull(stepsInsight)

        val heartInsight = report.insights.find { it.iconType == "heart" }
        assertNotNull(heartInsight)
        assertEquals(65, heartInsight!!.scoreDelta) // (62+68)/2 = 65
    }
}
