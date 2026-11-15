package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the private journal's session from a password (12.19: the journal and a meeting's prep share
 * it). The password is first verified against the notes; then the session opens, a typed password
 * is bound to the biometric key and the 12.17 readable copy deleted (12.18, V3), and the follow-ups
 * run. The caller wipes its own copy of the password.
 */
@Singleton
class PrivateVaultUnlocker @Inject constructor(
    private val repository: PrivateNoteRepository,
    private val session: PrivateVaultSession,
    private val binding: VaultPasswordBinding,
    private val followUps: PrivateVaultFollowUps,
    @param:ApplicationScope private val appScope: CoroutineScope
) {
    /** True once the session is open; false when [password] doesn't open the notes. */
    suspend fun unlock(password: CharArray, viaBiometric: Boolean): Boolean {
        if (password.isEmpty()) return false
        val candidate = password.clone()
        try {
            val valid = try {
                repository.verifyPassword(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!valid) return false
            session.open(candidate)
            // Binds a typed password and deletes the readable 12.17 copy (Keystore work: off Main).
            withContext(Dispatchers.IO) { binding.confirmUnlock(candidate, viaBiometric) }
            appScope.launch { followUps.afterUnlock() }
            return true
        } finally {
            Arrays.fill(candidate, '\u0000')
        }
    }
}
