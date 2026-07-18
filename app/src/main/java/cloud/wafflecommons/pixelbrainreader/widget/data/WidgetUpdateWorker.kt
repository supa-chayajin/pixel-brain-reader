package cloud.wafflecommons.pixelbrainreader.widget.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.repository.WidgetRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import android.util.Log

@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val widgetSnapshotManager: cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            widgetSnapshotManager.updateSnapshot()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal when a newer snapshot rebuild REPLACES this one (unique work) — not a failure.
            // Rethrow so WorkManager records a clean cancellation instead of an error + retry.
            throw e
        } catch (e: Exception) {
            Log.e("WidgetUpdateWorker", "Snapshot update failed", e)
            Result.retry()
        }
    }
}
