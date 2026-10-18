package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * The weekly objectives slot starts: a nudge naming what is due soon and what stalled — unless the
 * mode mutes work notifications (Personal, Off, a leave). Re-pins next week's run either way.
 */
@HiltWorker
class ObjectiveSlotWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val objectiveRepository: ObjectiveRepository,
    private val modesRepository: ModesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            if (!modesRepository.currentVisibility().muteWorkNotifications) {
                val today = LocalDate.now()
                val objectives = objectiveRepository.objectives(today)
                if (objectives.isNotEmpty()) {
                    val dueSoon = objectives.count { o -> o.nextDue?.due?.let { !it.isAfter(today.plusDays(14)) } == true }
                    val stalled = objectives.count { it.stale(today) }
                    val minutes = objectiveRepository.settings.value.slotMinutes
                    NotificationHelper.postObjectiveSlot(applicationContext, minutes, objectives.size, dueSoon, stalled)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ObjectiveSlotWorker", "Objective nudge failed (non-fatal)", e)
        }
        try {
            objectiveRepository.repinSlot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ObjectiveSlotWorker", "Could not re-pin the next slot", e)
        }
        return Result.success()
    }
}
