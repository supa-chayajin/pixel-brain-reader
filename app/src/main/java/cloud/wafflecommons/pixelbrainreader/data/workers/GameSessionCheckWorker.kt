package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * A look at the running game session (13.1): "Still playing?" once it is due, and the end of a
 * session nobody answered for ([cloud.wafflecommons.pixelbrainreader.data.games.GameSessionGuard]).
 * One unique job, scheduled by the session itself; nothing on a read-only device.
 */
@HiltWorker
class GameSessionCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val controller: GameSessionController
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (VaultWriteGate.readOnly) return Result.success()
        try {
            controller.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GameSessionCheck", "Game session check failed (non-fatal): ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}
