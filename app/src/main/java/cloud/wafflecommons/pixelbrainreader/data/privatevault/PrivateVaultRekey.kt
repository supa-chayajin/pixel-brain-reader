package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changing the private journal's password (12.18, V1; owner, 2026-11-15). [VaultRekeyEngine] does
 * the crash-safe swap; around it, this holds the git lock (no sync, push or commit meanwhile) and
 * the session's write lock (no encrypted write), keeps the password from everyone else until the
 * swap ends, then re-encrypts the AI index's private passages, binds the new password to the
 * biometric key and asks for a push. Git history keeps the old versions under the old password (D1).
 */
@Singleton
class PrivateVaultRekey @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
    private val session: PrivateVaultSession,
    private val binding: VaultPasswordBinding,
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val embeddingDao: EmbeddingDao,
    private val database: AppDatabase,
    private val pushScheduler: VaultPushScheduler
) {
    sealed interface Result {
        data class Changed(val files: Int) : Result
        data object WrongPassword : Result
        data object Locked : Result
        data object ReadOnly : Result

        /** Notes the current password doesn't open, by name: nothing was changed. */
        data class Unreadable(val names: List<String>) : Result

        /** Something changed a note meanwhile, or the change failed before its swap: nothing was changed. */
        data class NotChanged(val reason: String) : Result
    }

    private fun engine(): VaultRekeyEngine =
        VaultRekeyEngine(File(context.filesDir, "vault"), RekeyFiles.workDir(context.filesDir), crypto)

    /** [current] must be the unlocked journal's own password: typed again, as for any change of password. */
    suspend fun change(current: CharArray, new: CharArray, progress: (Int, Int) -> Unit = { _, _ -> }): Result =
        withContext(Dispatchers.IO) {
            if (VaultWriteGate.refuses()) return@withContext Result.ReadOnly
            val open = session.password() ?: return@withContext Result.Locked
            val matches = open.contentEquals(current)
            open.fill('\u0000')
            if (!matches) return@withContext Result.WrongPassword

            val result = gitSyncCoordinator.mutex.withLock {
                session.writeLock.withLock {
                    session.beginChange()
                    var changedTo: CharArray? = null
                    try {
                        // A swap a crash left half done is finished first: its notes are on its new password.
                        if (engine().resume() == VaultRekeyEngine.Resumed.STUCK) {
                            return@withLock Result.NotChanged("A previous change of password couldn't be finished.")
                        }
                        when (val outcome = engine().change(current, new, progress)) {
                            is VaultRekeyEngine.Outcome.Done -> {
                                changedTo = new
                                reencryptPassages(current, new)
                                Result.Changed(outcome.files)
                            }
                            is VaultRekeyEngine.Outcome.Unreadable -> Result.Unreadable(outcome.paths.map(::nameOf))
                            VaultRekeyEngine.Outcome.Moved -> Result.NotChanged("A note changed meanwhile: try again.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "The change of password failed: ${e.javaClass.simpleName}")
                        // Before the swap the vault is untouched; once it started, the swap finishes forward.
                        when (runCatching { engine().resume() }.getOrNull()) {
                            VaultRekeyEngine.Resumed.FINISHED -> {
                                changedTo = new
                                dropPrivatePassages()
                                Result.Changed(engine().encryptedFiles().size)
                            }
                            else -> Result.NotChanged("The password couldn't be changed: nothing was changed.")
                        }
                    } finally {
                        session.endChange(changedTo)
                    }
                }
            }
            if (result is Result.Changed) {
                binding.bind(new) // silently, with the key's public half; without a strong biometric nothing is kept
                pushScheduler.requestPush()
            }
            result
        }

    /**
     * At start: a change a crash interrupted is rolled back (before its swap) or finished (after),
     * before anything commits the vault.
     */
    suspend fun resumeAtStart() = withContext(Dispatchers.IO) {
        if (!RekeyFiles.workDir(context.filesDir).exists()) return@withContext
        gitSyncCoordinator.mutex.withLock {
            when (engine().resume()) {
                VaultRekeyEngine.Resumed.FINISHED -> {
                    Log.i(TAG, "A change of password interrupted by a crash was finished")
                    // Its passages are still on the old password, which nothing holds any more.
                    dropPrivatePassages()
                    pushScheduler.requestPush()
                }
                VaultRekeyEngine.Resumed.ROLLED_BACK -> Log.i(TAG, "A change of password interrupted before its swap was dropped")
                else -> Unit
            }
        }
    }

    /** The AI index keeps private passages encrypted with the vault password: they follow it. */
    private suspend fun reencryptPassages(current: CharArray, new: CharArray) {
        try {
            val rows = embeddingDao.privateContents()
            val updated = rows.mapNotNull { row ->
                try {
                    val text = crypto.decrypt(Base64.decode(row.content, Base64.NO_WRAP), current)
                    row.id to Base64.encodeToString(crypto.encrypt(text, new), Base64.NO_WRAP)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // already unreadable: the indexer embeds it again
                }
            }
            database.withTransaction { updated.forEach { (id, content) -> embeddingDao.setContent(id, content) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Private passages couldn't follow the new password: dropped, they will be embedded again")
            dropPrivatePassages()
        }
    }

    private suspend fun dropPrivatePassages() {
        try {
            embeddingDao.deletePrivate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Private passages couldn't be dropped: ${e.javaClass.simpleName}")
        }
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/').removeSuffix(".md.enc")

    private companion object {
        const val TAG = "PrivateVault"
    }
}
