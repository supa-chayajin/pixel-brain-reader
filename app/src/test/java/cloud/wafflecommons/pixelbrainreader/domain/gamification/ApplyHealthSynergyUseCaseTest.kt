package cloud.wafflecommons.pixelbrainreader.domain.gamification

import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ApplyHealthSynergyUseCaseTest {

    private val today = LocalDate.of(2026, 10, 17)
    private val ledger = MutableStateFlow("")
    private val prefs: GamificationPreferences = mockk {
        every { lastHealthSynergyAppliedDateFlow } returns ledger
        every { stepTargetFlow } returns flowOf(10_000)
        every { sleepMinMinutesFlow } returns flowOf(420)
        coEvery { setLastHealthSynergyAppliedDate(any()) } answers { ledger.value = firstArg() }
    }
    private val grantXp: GrantXpUseCase = mockk(relaxed = true)
    private val moods: MoodRepository = mockk { every { getMoodFlow() } returns flowOf(emptyList()) }
    private val health: HealthMetricsRepository = mockk()
    private val useCase = ApplyHealthSynergyUseCase(prefs, grantXp, moods, health)

    private fun metrics(steps: Long, sleepMinutes: Long) =
        DailyHealthMetrics(date = today.toString(), steps = steps, sleepDurationMinutes = sleepMinutes, averageHeartRate = 60)

    @Test
    fun `the morning sleep bonus no longer locks out the step bonus earned later that day`() = runBlocking {
        // 08:00 — slept well, few steps yet.
        coEvery { health.getMetrics(today) } returns metrics(steps = 1_200, sleepMinutes = 450)
        useCase(today)
        // 19:00 — step goal reached.
        coEvery { health.getMetrics(today) } returns metrics(steps = 11_000, sleepMinutes = 450)
        useCase(today)
        // 21:00 — another refresh: nothing new to grant.
        useCase(today)

        coVerify(exactly = 1) { grantXp.executeCustom(Attribute.MND, 25.0, "Health Synergy: Sleep Goal Reached") }
        coVerify(exactly = 1) { grantXp.executeCustom(Attribute.VIG, 50.0, "Health Synergy: Step Goal Reached") }
        assertEquals("2026-10-17:sleep,steps", ledger.value)
    }

    @Test
    fun `a legacy all-or-nothing flag for today grants nothing again`() = runBlocking {
        ledger.value = "2026-10-17"
        coEvery { health.getMetrics(today) } returns metrics(steps = 11_000, sleepMinutes = 450)

        useCase(today)

        coVerify(exactly = 0) { grantXp.executeCustom(any(), any(), any()) }
    }

    @Test
    fun `a new day starts with every bonus available`() {
        assertTrue(HealthBonusLedger.grantedOn("2026-10-16:sleep,steps", today).isEmpty())
        assertEquals(setOf("steps"), HealthBonusLedger.grantedOn("2026-10-17:steps", today))
        assertEquals("2026-10-17:sleep,steps,synergy", HealthBonusLedger.record(today, setOf("synergy", "steps", "sleep")))
    }
}
