package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

@HiltWorker
class DailyExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dashboardRepository: DailyDashboardRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("DailyExportWorker", "Starting automated Daily Burn process...")
        return try {
            // New logic: Perform full retroactive export, catching missing days.
            // This replaces the simple burnToDisk(LocalDate.now()) to guarantee completely consistent history.
            dashboardRepository.performRetroactiveExport()
            Log.i("DailyExportWorker", "Daily Burn and Retroactive Recovery completed successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e("DailyExportWorker", "Failed to burn daily metrics", e)
            Result.retry()
        }
    }
}
