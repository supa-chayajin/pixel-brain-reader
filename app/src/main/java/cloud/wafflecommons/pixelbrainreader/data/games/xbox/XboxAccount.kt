package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last Xbox sync's outcome, shown on the Games tab and the settings page (pure). Kept on the
 * phone as `ok|<millis>` or `paused|<kind>|<millis>|<message>` (PsnStatus's format).
 */
data class XboxStatus(val ok: Boolean, val atMillis: Long, val kind: XboxException.Kind? = null, val message: String = "") {

    /** A paused sync needs the owner: a new sign-in, a corrected app registration, or the account itself. */
    val needsOwner: Boolean
        get() = !ok && kind in OWNER_KINDS

    /** Only a new sign-in helps. */
    val needsSignIn: Boolean
        get() = !ok && kind == XboxException.Kind.SIGN_IN_EXPIRED

    fun encode(): String = if (ok) "ok|$atMillis" else "paused|${kind?.name.orEmpty()}|$atMillis|${message.replace('\n', ' ')}"

    companion object {
        private val OWNER_KINDS = setOf(
            XboxException.Kind.SIGN_IN_EXPIRED,
            XboxException.Kind.CLIENT_REJECTED,
            XboxException.Kind.ACCOUNT_REFUSED
        )

        fun decode(raw: String?): XboxStatus? {
            val parts = raw?.split('|', limit = 4) ?: return null
            return when (parts.firstOrNull()) {
                "ok" -> parts.getOrNull(1)?.toLongOrNull()?.let { XboxStatus(true, it) }
                "paused" -> XboxStatus(
                    ok = false,
                    atMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    kind = XboxException.Kind.entries.firstOrNull { it.name == parts.getOrNull(1) },
                    message = parts.getOrNull(3).orEmpty()
                )
                else -> null
            }
        }
    }
}

/**
 * The owner's Xbox sign-in (13.3, XB1): the owner's own Microsoft app, by device code. Only
 * Microsoft's tokens are kept, in encrypted storage (SecretManager); the Xbox user token, the XSTS
 * token and the xuid are derived again at each sync and stay in memory. The refresh token replaces
 * itself at every use: the new one is saved before anything else is asked. Never on a read-only
 * device: everything Xbox brings is written to the vault.
 */
@Singleton
class XboxAccount @Inject constructor(
    private val client: XboxClient,
    private val secrets: SecretManager,
    private val preferences: UserPreferencesRepository
) {
    private val lock = Mutex()

    /** The session of the last sync, until 5 min before it ends. Read and written under [lock]. */
    private var cached: XboxSession? = null

    /** False when this build has no Xbox app: the Xbox row is hidden. */
    val configured: Boolean get() = client.configured

    val status: Flow<XboxStatus?> = preferences.xboxStatus.map(XboxStatus::decode)

    suspend fun tokens(): XboxTokens? = withContext(Dispatchers.IO) { secrets.getXboxTokens() }

    suspend fun isConnected(): Boolean = tokens() != null

    /** Step 1 of Connect: a code for microsoft.com/link. Refused on a read-only device. */
    suspend fun startSignIn(now: Long = System.currentTimeMillis()): Result<XboxDeviceCode> {
        if (VaultWriteGate.refuses()) return Result.failure(readOnly())
        return try {
            Result.success(client.startDeviceCode(now))
        } catch (e: XboxException) {
            Log.i(TAG, "Xbox connect refused: ${e.kind}")
            Result.failure(e)
        }
    }

    /** Step 2: polls until the owner signs in, declines, or the code expires; saves the tokens. */
    suspend fun awaitSignIn(code: XboxDeviceCode, clock: () -> Long = System::currentTimeMillis): Result<Unit> {
        if (VaultWriteGate.readOnly) return Result.failure(readOnly())
        var interval = code.intervalSeconds
        while (clock() < code.expiresAt) {
            delay(interval * 1000)
            val poll = try {
                client.pollDeviceCode(code, clock())
            } catch (e: XboxException) {
                // A dropped connection while the owner types the code: keep waiting.
                if (e.kind == XboxException.Kind.NETWORK) continue
                Log.i(TAG, "Xbox connect stopped: ${e.kind}")
                return Result.failure(e)
            }
            when (poll) {
                DevicePoll.Pending -> Unit
                DevicePoll.SlowDown -> interval += SLOW_DOWN_SECONDS
                is DevicePoll.Done -> {
                    // No sign-in kept (the first Connect, or one after Disconnect or a log-out): play
                    // counts from now on, so the syncs are baselines until one is saved. Marked before
                    // the tokens are kept: no sync ever sees the new sign-in without it.
                    if (!isConnected()) preferences.setXboxBaselinePending(true)
                    withContext(Dispatchers.IO) { secrets.saveXboxTokens(poll.tokens) }
                    lock.withLock { cached = null }
                    preferences.setXboxStatus(XboxStatus(true, clock()).encode())
                    Log.i(TAG, "Xbox connect: signed in")
                    return Result.success(Unit)
                }
                DevicePoll.Declined -> return Result.failure(
                    XboxException(XboxException.Kind.ACCOUNT_REFUSED, "The sign-in was declined at microsoft.com/link.")
                )
                DevicePoll.Expired -> return Result.failure(expired())
            }
        }
        return Result.failure(expired())
    }

    /** Forgets the sign-in: the tokens, the session in memory and the status line. */
    suspend fun disconnect() {
        // Under the session's lock: a renewal in flight saves its tokens first, then the clear wins.
        lock.withLock {
            withContext(Dispatchers.IO) { secrets.clearXboxTokens() }
            cached = null
        }
        preferences.setXboxStatus(null)
    }

    /**
     * The session for data calls, kept in memory until 5 min before it ends; [force] renews it all
     * (after a data call's 401). An access token ending within a minute is renewed first; a refused
     * one is renewed once, then the sign-in has ended.
     */
    suspend fun session(now: Long = System.currentTimeMillis(), force: Boolean = false): XboxSession = lock.withLock {
        val kept = cached
        if (!force && kept != null && kept.expiresAt - now > SESSION_MARGIN_MS) return@withLock kept
        var tokens = tokens() ?: throw XboxException(XboxException.Kind.NOT_CONFIGURED, "Xbox isn't connected.")
        if (force || tokens.accessExpiresAt - now <= ACCESS_MARGIN_MS) tokens = renew(tokens, now)
        val fresh = try {
            client.session(tokens.access)
        } catch (e: XboxException) {
            if (e.kind != XboxException.Kind.TOKEN_EXPIRED) throw e
            tokens = renew(tokens, now)
            try {
                client.session(tokens.access)
            } catch (again: XboxException) {
                if (again.kind != XboxException.Kind.TOKEN_EXPIRED) throw again
                Log.i(TAG, "Xbox session: the renewed access token was refused too")
                throw XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in was refused: Connect again.")
            }
        }
        cached = fresh
        fresh
    }

    suspend fun setStatus(status: XboxStatus) = preferences.setXboxStatus(status.encode())

    /** A new sign-in whose first sync isn't saved yet: every sync until then is a baseline (13.3). */
    suspend fun baselinePending(): Boolean = preferences.xboxBaselinePending.first()

    /** The baseline's snapshot is saved: from the next sync on, the play since counts. */
    suspend fun baselineSaved() = preferences.setXboxBaselinePending(false)

    /**
     * A new access token, and the rotated refresh token saved before any other call; saved only while
     * the stored sign-in is still the one renewed. Forgotten while Microsoft answered (a log-out
     * clears the phone's secrets outside this lock), nothing is saved and Xbox isn't connected.
     */
    private suspend fun renew(tokens: XboxTokens, now: Long): XboxTokens {
        val fresh = client.refresh(tokens, now)
        val saved = withContext(Dispatchers.IO) {
            if (secrets.getXboxTokens()?.refresh != tokens.refresh) {
                false
            } else {
                secrets.saveXboxTokens(fresh)
                true
            }
        }
        if (!saved) {
            Log.i(TAG, "Xbox session: the sign-in was forgotten during its renewal")
            throw XboxException(XboxException.Kind.NOT_CONFIGURED, "Xbox isn't connected.")
        }
        return fresh
    }

    private fun readOnly() = XboxException(XboxException.Kind.NOT_CONFIGURED, "Read-only device: connect Xbox on your phone.")

    private fun expired() = XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The code expired: Connect again.")

    private companion object {
        const val TAG = "XboxAccount"
        const val SLOW_DOWN_SECONDS = 5L
        const val SESSION_MARGIN_MS = 5 * 60_000L
        const val ACCESS_MARGIN_MS = 60_000L
    }
}
