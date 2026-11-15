package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The private journal is locked: its password is only in memory while it is unlocked (12.18, V3). */
class PrivateVaultLockedException(message: String = "Unlock the private journal first.") : IOException(message)

/** One operation's hold on the password: a copy the caller wipes once the operation is done. */
class PrivateVaultKey internal constructor(internal val password: CharArray) {
    fun wipe() = password.fill('\u0000')
}

/**
 * The private journal's session (12.18, V3): the password in memory from an unlock until a lock, the
 * only place it is ever readable. Nothing reads it in the background any more: the indexer, Chat's
 * private answers and an encrypted mission log ask the session, and do without while it is locked.
 *
 * Encrypted writes run under [writeLock].
 */
@Singleton
class PrivateVaultSession @Inject constructor(private val cryptoManager: CryptoManager) {

    private val guard = Any()

    // Guarded by [guard].
    private var password: CharArray? = null

    private val _unlocked = MutableStateFlow(false)

    /** True while the journal is unlocked. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Encrypted writes take it, so two of them never interleave. */
    val writeLock = Mutex()

    /** Opens the session with [password], already verified against the notes. The caller keeps its own copy. */
    fun open(password: CharArray) {
        synchronized(guard) {
            this.password?.fill('\u0000')
            this.password = password.copyOf()
            _unlocked.value = true
        }
    }

    /** True when the password can be used now. */
    fun isOpen(): Boolean = synchronized(guard) { password != null }

    /** A key for one operation, or null while locked. The caller wipes it. */
    fun key(): PrivateVaultKey? = synchronized(guard) { password?.let { PrivateVaultKey(it.copyOf()) } }

    /** A copy of the password for one read, or null while locked. The caller wipes it. */
    fun password(): CharArray? = synchronized(guard) { password?.copyOf() }

    /** Runs [block] with the password under [writeLock]; [locked] is thrown while the session is locked. */
    suspend fun <T> withPassword(
        locked: () -> Exception = { PrivateVaultLockedException() },
        block: suspend (CharArray) -> T
    ): T = writeLock.withLock {
        val current = password() ?: throw locked()
        try {
            block(current)
        } finally {
            current.fill('\u0000')
        }
    }

    /** Runs a write under [writeLock] with [key]'s password: a save taken just before a lock still lands. */
    suspend fun <T> writeWith(key: PrivateVaultKey, block: suspend (CharArray) -> T): T = writeLock.withLock {
        val current = key.password.copyOf()
        try {
            block(current)
        } finally {
            current.fill('\u0000')
        }
    }

    /** Wipes the password and the keys derived from it. */
    fun lock() {
        synchronized(guard) {
            password?.fill('\u0000')
            password = null
            _unlocked.value = false
        }
        cryptoManager.clearKeyCache()
    }
}
