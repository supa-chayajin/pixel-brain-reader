package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The PlayStation and Xbox syncs (RFC-009 §7, D3; XB6), twice a day, each slot pinned to its time
 * like the burn: at 23:40, so the 23:50 burn writes the evening's game lines, and at 06:00 for the
 * sessions that ran past midnight (D11). PSN runs first, then Xbox, each on its own: one failing
 * (or not connected) never stops the other. A failure waits for the next slot; it never touches
 * the vault sync. Nothing runs on a read-only device.
 */
@HiltWorker
class GamesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val psnSync: PsnSync,
    private val xboxSync: XboxSync
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT) ?: EVENING
        if (!VaultWriteGate.readOnly) {
            syncOne("PSN", slot) { psnSync.sync() }
            syncOne("Xbox", slot) { xboxSync.sync() }
        }
        try {
            DailyWorkScheduler.repin(WorkManager.getInstance(applicationContext), uniqueName(slot)) { buildRequest(slot) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-pin the Games sync ($slot)", e)
        }
        return Result.success()
    }

    /** One console's sync: its failure is logged and the next console still runs. */
    private suspend fun syncOne(console: String, slot: String, sync: suspend () -> kotlin.Result<*>) {
        try {
            sync().onFailure { Log.i(TAG, "$console sync ($slot) skipped: ${it.message}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$console sync ($slot) failed", e)
        }
    }

    companion object {
        private const val TAG = "GamesSyncWorker"
        private const val KEY_SLOT = "slot"
        const val EVENING = "evening"
        const val MORNING = "morning"
        val SLOTS = listOf(EVENING, MORNING)

        fun timeOf(slot: String): LocalTime = if (slot == MORNING) LocalTime.of(6, 0) else LocalTime.of(23, 40)

        fun uniqueName(slot: String): String = "GamesSyncWorker_$slot"

        fun buildRequest(slot: String): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<GamesSyncWorker>(24, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_SLOT to slot))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setNextScheduleTimeOverride(DailySchedule.nextOccurrenceMillis(timeOf(slot)))
                .build()

        /** Both slots, KEEP-based (never replaces a run that may be due now). */
        suspend fun ensureScheduled(workManager: WorkManager) {
            SLOTS.forEach { slot -> DailyWorkScheduler.ensureScheduled(workManager, uniqueName(slot), timeOf(slot)) { buildRequest(slot) } }
        }

        fun cancel(workManager: WorkManager) {
            SLOTS.forEach { workManager.cancelUniqueWork(uniqueName(it)) }
        }
    }
}
