package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts [IndexingWorker] under its unique name. The login uses it: a new vault used to wait for
 * the next app start to be embedded, so search found nothing on a freshly logged-in device.
 */
@Singleton
class IndexingScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** Unless a run is already queued or running (it carries on). Never on a read-only device: no AI there. */
    fun start() {
        if (VaultWriteGate.readOnly) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            IndexingWorker.request()
        )
    }
}
