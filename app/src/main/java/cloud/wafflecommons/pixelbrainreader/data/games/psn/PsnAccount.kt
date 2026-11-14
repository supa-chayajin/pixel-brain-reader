package cloud.wafflecommons.pixelbrainreader.data.games.psn

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last PSN sync's outcome, shown on the Games tab and the settings page (pure). Kept on the
 * phone as `ok|<millis>` or `paused|<kind>|<millis>|<message>`.
 */
data class PsnStatus(val ok: Boolean, val atMillis: Long, val kind: PsnException.Kind? = null, val message: String = "") {

    /** A paused sync needs the owner: a new NPSSO, or PSN can't be used for now. */
    val needsOwner: Boolean
        get() = !ok && kind in setOf(PsnException.Kind.TOKEN_EXPIRED, PsnException.Kind.NPSSO_REJECTED, PsnException.Kind.CLIENT_REJECTED)

    fun encode(): String = if (ok) "ok|$atMillis" else "paused|${kind?.name.orEmpty()}|$atMillis|${message.replace('\n', ' ')}"

    companion object {
        fun decode(raw: String?): PsnStatus? {
            val parts = raw?.split('|', limit = 4) ?: return null
            return when (parts.firstOrNull()) {
                "ok" -> parts.getOrNull(1)?.toLongOrNull()?.let { PsnStatus(true, it) }
                "paused" -> PsnStatus(
                    ok = false,
                    atMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    kind = PsnException.Kind.entries.firstOrNull { it.name == parts.getOrNull(1) },
                    message = parts.getOrNull(3).orEmpty()
                )
                else -> null
            }
        }
    }
}

/**
 * The owner's PSN sign-in (RFC-009 §7, D10): the NPSSO is exchanged at once and forgotten, the
 * tokens live in encrypted storage, and the access token (an hour) is refreshed when needed.
 * Never on a read-only device: everything PSN brings is written to the vault.
 */
@Singleton
class PsnAccount @Inject constructor(
    private val client: PsnClient,
    private val secrets: SecretManager,
    private val preferences: UserPreferencesRepository
) {
    private val lock = Mutex()

    /** False when this build has no PSN client: the PSN row is hidden. */
    val configured: Boolean get() = client.configured

    val status: Flow<PsnStatus?> = preferences.psnStatus.map(PsnStatus::decode)

    suspend fun tokens(): PsnTokens? = withContext(Dispatchers.IO) { secrets.getPsnTokens() }

    suspend fun isConnected(): Boolean = tokens() != null

    /** Signs in with what the owner pasted (the NPSSO, or the page's whole answer). */
    suspend fun connect(pasted: String, now: Long = System.currentTimeMillis()): Result<Unit> {
        if (VaultWriteGate.refuses()) {
            return Result.failure(PsnException(PsnException.Kind.NOT_CONFIGURED, "Read-only device: connect PlayStation on your phone."))
        }
        val npsso = PsnClient.cleanNpsso(pasted)
        if (npsso == null) {
            // What was pasted is described, never logged nor shown.
            Log.i(TAG, "PSN connect: the paste isn't an NPSSO")
            return Result.failure(
                PsnException(
                    PsnException.Kind.NPSSO_REJECTED,
                    "That doesn't look like an NPSSO (${PsnClient.describeRefused(pasted)}). Copy the value after \"npsso\" on the NPSSO page."
                )
            )
        }
        return try {
            val tokens = client.signIn(npsso, now)
            withContext(Dispatchers.IO) { secrets.savePsnTokens(tokens) }
            preferences.setPsnStatus(PsnStatus(true, now).encode())
            Log.i(TAG, "PSN connect: signed in")
            Result.success(Unit)
        } catch (e: PsnException) {
            Log.i(TAG, "PSN connect refused: ${e.kind}")
            Result.failure(e)
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) { secrets.clearPsnTokens() }
        preferences.setPsnStatus(null)
    }

    /** A valid access token, refreshed when it expires within a minute (or when [force]d after a 401). */
    suspend fun accessToken(now: Long = System.currentTimeMillis(), force: Boolean = false): String = lock.withLock {
        val tokens = tokens() ?: throw PsnException(PsnException.Kind.NOT_CONFIGURED, "PlayStation isn't connected.")
        if (!force && tokens.accessExpiresAt - now > 60_000L) return@withLock tokens.access
        val fresh = client.refresh(tokens, now)
        withContext(Dispatchers.IO) { secrets.savePsnTokens(fresh) }
        fresh.access
    }

    suspend fun setStatus(status: PsnStatus) = preferences.setPsnStatus(status.encode())

    private companion object {
        const val TAG = "PsnAccount"
    }
}
