package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.review.MonthlyReportsRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What waited for the password (12.18, V3): with the password behind a biometric, background work
 * can't read it, so the work that needs it runs after an unlock instead.
 * - Private notes in AI answers (when on): their embedding, while the session stays open.
 * - An encrypted mission log's monthly work report, which the 23:50 run couldn't write.
 */
@Singleton
class PrivateVaultFollowUps @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val indexingScheduler: IndexingScheduler,
    private val missionLogRepository: dagger.Lazy<MissionLogRepository>,
    private val monthlyReportsRepository: dagger.Lazy<MonthlyReportsRepository>
) {
    suspend fun afterUnlock() {
        if (VaultWriteGate.readOnly) return
        step("indexing") {
            if (preferences.privateNotesInAi.first()) indexingScheduler.startAfterCurrent()
        }
        step("monthly report") {
            if (missionLogRepository.get().reloadSettings().encrypted) monthlyReportsRepository.get().writeDue()
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "After the unlock, $name failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "PrivateVault"
    }
}
