package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.os.RemoteException
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncHealthDataUseCase: SyncHealthDataUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val yesterday = LocalDate.now().minusDays(1)
            val result = syncHealthDataUseCase(yesterday)
            
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: RemoteException) {
            Result.success()
        } catch (e: SecurityException) {
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
