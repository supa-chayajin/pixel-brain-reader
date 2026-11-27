package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Games sync's two slots, kept while PlayStation or Xbox is connected (13.3). One worker runs
 * both consoles (XB6), so disconnecting one console keeps the slots while the other is connected;
 * they go once neither is.
 */
@Singleton
class GamesSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val psn: PsnAccount,
    private val xbox: XboxAccount
) {
    /** Both slots (KEEP-based) while either console is connected; none once neither is; nothing on a read-only device. */
    suspend fun update() {
        if (VaultWriteGate.readOnly) return
        val workManager = WorkManager.getInstance(context)
        val connected = (psn.configured && psn.isConnected()) || (xbox.configured && xbox.isConnected())
        if (connected) GamesSyncWorker.ensureScheduled(workManager) else GamesSyncWorker.cancel(workManager)
    }
}
