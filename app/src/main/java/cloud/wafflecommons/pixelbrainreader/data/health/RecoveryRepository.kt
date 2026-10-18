package cloud.wafflecommons.pixelbrainreader.data.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Nights of recovery data, oldest first, and whether Health Connect still lacks the grants for it. */
data class RecoveryHistory(val nights: List<RecoveryDay>, val needsPermission: Boolean)

/**
 * Recovery signals over weeks: Health Connect when it answers (a foreground app, with the grants),
 * the vault's daily metrics for every night it doesn't cover. The vault alone would need four
 * weeks of v11 syncs before the first baseline.
 */
@Singleton
class RecoveryRepository @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val healthMetricsRepository: HealthMetricsRepository
) {

    suspend fun history(last: LocalDate, nights: Int): RecoveryHistory {
        val first = last.minusDays(nights.toLong() - 1)
        val available = healthConnectManager.getSdkStatus() == HealthConnectClient.SDK_AVAILABLE
        val live = if (available) {
            try {
                healthConnectManager.readRecoveryNights(first, last)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Recovery history unavailable from Health Connect", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        val stored = healthMetricsRepository.getMetricsHistoryFlow(last, nights).first().mapNotNull(RecoveryDay::from)
        return RecoveryHistory(
            nights = merge(live, stored),
            needsPermission = available && !healthConnectManager.hasRecoveryPermissions()
        )
    }

    fun requiredPermissions(): Set<String> = healthConnectManager.getRequiredPermissions()

    companion object {
        private const val TAG = "RecoveryRepository"

        /** One night per date, oldest first; per signal, Health Connect's value wins over the vault's. */
        fun merge(live: List<RecoveryDay>, stored: List<RecoveryDay>): List<RecoveryDay> {
            val liveByDate = live.associateBy { it.date }
            val storedByDate = stored.associateBy { it.date }
            return (liveByDate.keys + storedByDate.keys).sorted().map { date ->
                val a = liveByDate[date]
                val b = storedByDate[date]
                RecoveryDay(
                    date = date,
                    restingHeartRate = a?.restingHeartRate ?: b?.restingHeartRate,
                    hrvMs = a?.hrvMs ?: b?.hrvMs,
                    sleepMinutes = a?.sleepMinutes ?: b?.sleepMinutes,
                    stages = a?.stages ?: b?.stages
                )
            }
        }
    }
}
