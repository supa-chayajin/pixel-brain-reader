package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.util.concurrent.TimeUnit

@HiltWorker
class DailyExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dashboardRepository: DailyDashboardRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("DailyExportWorker", "Starting automated Daily Burn process...")
        return try {
            // Full retroactive export: burns today and any past day whose markdown is missing.
            dashboardRepository.performRetroactiveExport()
            Log.i("DailyExportWorker", "Daily Burn and Retroactive Recovery completed successfully.")
            repinNextRun()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cap retries so a DETERMINISTIC failure (malformed row, bug) can't retry with backoff
            // forever — mirrors IndexingWorker's runAttemptCount < 3 policy. No re-pin on retry: a
            // schedule override takes priority over backoff and would skip the retry entirely.
            Log.e("DailyExportWorker", "Failed to burn daily metrics (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                repinNextRun()
                Result.failure()
            }
        }
    }

    /** Pins the next run back to [BURN_TIME] so the nightly burn never drifts past midnight. */
    private fun repinNextRun() {
        try {
            DailyWorkScheduler.repin(WorkManager.getInstance(applicationContext), UNIQUE_WORK_NAME) { buildRequest() }
        } catch (e: Exception) {
            Log.w("DailyExportWorker", "Could not re-pin the next daily burn", e)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "DailyExportWorker"

        /** 23:50 — the last practical moment of the day, so the complete day is burned to markdown. */
        val BURN_TIME: LocalTime = LocalTime.of(23, 50)

        // NO constraints: the burn is a cheap, purely-local markdown write and MUST fire every day.
        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<DailyExportWorker>(24, TimeUnit.HOURS)
                .setNextScheduleTimeOverride(DailySchedule.nextOccurrenceMillis(BURN_TIME))
                .build()
    }
}
