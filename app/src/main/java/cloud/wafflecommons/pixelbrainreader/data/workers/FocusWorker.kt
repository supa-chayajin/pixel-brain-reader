package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.focus.FocusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** One step of the focus session (D9): the bar moves on every five minutes, or the session ends. */
@HiltWorker
class FocusWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FocusRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            repository.step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FocusWorker", "Focus step failed (non-fatal)", e)
        }
        return Result.success()
    }
}
