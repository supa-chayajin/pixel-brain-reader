package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.os.SystemClock
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The private journal is locked: its password is only in memory while it is unlocked (12.18, V3). */
class PrivateVaultLockedException(message: String = "Unlock the private journal first.") : IOException(message)

/**
 * One operation's hold on the password: a copy the caller wipes once the operation is done, and the
 * password's generation (a change of password bumps it, so a save taken before the change writes
 * with the new password instead of the old one).
 */
class PrivateVaultKey internal constructor(internal val password: CharArray, internal val generation: Long = 0) {
    fun wipe() = password.fill('\u0000')
}

/**
 * The private journal's session (12.18, V3): the password in memory from an unlock until a lock, the
 * only place it is ever readable. Nothing reads it in the background any more: the indexer, Chat's
 * private answers and an encrypted mission log ask the session, and do without while it is locked.
 *
 * It locks by itself ([AutoLock], V2): a screen showing private content holds it ([hold] / [release]),
 * and once none does, it locks after the owner's delay (5 minutes by default). Every read checks the
 * deadline first, so a phone that slept through the timer still locks on its next read.
 *
 * Encrypted writes run under [writeLock], which a change of password holds from its check to its last
 * rename (V1); meanwhile nobody gets the password.
 */
@Singleton
class PrivateVaultSession @Inject constructor(
    private val cryptoManager: CryptoManager,
    preferences: UserPreferencesRepository,
    @param:ApplicationScope private val appScope: CoroutineScope
) {

    private val guard = Any()

    // Guarded by [guard].
    private var password: CharArray? = null
    private var generation = 0L
    private var changing = false
    private val autoLock = AutoLock()
    private var timer: Job? = null

    @Volatile
    private var autoLockMs = AutoLock.DEFAULT_MINUTES * MINUTE_MS

    private val _unlocked = MutableStateFlow(false)

    /** True while the journal is unlocked. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Encrypted writes take it, so two of them never interleave; a change of password holds it throughout. */
    val writeLock = Mutex()

    init {
        appScope.launch { preferences.privateAutoLockMinutes.collect { autoLockMs = AutoLock.minutesOrDefault(it) * MINUTE_MS } }
    }

    /** Opens the session with [password], already verified against the notes. The caller keeps its own copy. */
    fun open(password: CharArray) {
        val deadline = synchronized(guard) {
            this.password?.fill('\u0000')
            this.password = password.copyOf()
            _unlocked.value = true
            autoLock.opened(now(), autoLockMs)
        }
        deadline?.let { schedule(it) }
    }

    /** True when the password can be used now (unlocked, and no change of password running). */
    fun isOpen(): Boolean {
        expireIfDue()
        return synchronized(guard) { password != null && !changing }
    }

    /** A key for one operation, or null while locked. The caller wipes it. */
    fun key(): PrivateVaultKey? {
        expireIfDue()
        return synchronized(guard) { password?.takeUnless { changing }?.let { PrivateVaultKey(it.copyOf(), generation) } }
    }

    /** A copy of the password for one read, or null while locked. The caller wipes it. */
    fun password(): CharArray? {
        expireIfDue()
        return synchronized(guard) { password?.takeUnless { changing }?.copyOf() }
    }

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

    /**
     * Runs a write under [writeLock] with [key]'s password: a save taken just before a lock still lands.
     * A key from before a change of password writes with the new one, and only while the journal is
     * unlocked: once locked, nothing may write with the old one.
     */
    suspend fun <T> writeWith(key: PrivateVaultKey, block: suspend (CharArray) -> T): T = writeLock.withLock {
        val current = synchronized(guard) {
            if (key.generation == generation) key.password.copyOf() else password?.copyOf()
        } ?: throw PrivateVaultLockedException()
        try {
            block(current)
        } finally {
            current.fill('\u0000')
        }
    }

    /** A screen showing private content came up (the journal's onStart): no countdown while it shows. */
    fun hold() {
        val expired = synchronized(guard) {
            timer?.cancel()
            timer = null
            autoLock.hold(now()) && password != null
        }
        if (expired) lock()
    }

    /** That screen left (the journal's onStop): once none shows, the session locks after the delay. */
    fun release() {
        val deadline = synchronized(guard) { autoLock.release(now(), autoLockMs)?.takeIf { password != null } }
        deadline?.let { schedule(it) }
    }

    /** Wipes the password and the keys derived from it. */
    fun lock() {
        synchronized(guard) {
            password?.fill('\u0000')
            password = null
            autoLock.locked()
            timer?.cancel()
            timer = null
            _unlocked.value = false
        }
        cryptoManager.clearKeyCache()
    }

    /** A change of password starts (V1): nobody else gets the password until [endChange]. Call it under [writeLock]. */
    internal fun beginChange() {
        synchronized(guard) { changing = true }
    }

    /** The change ended: with its [newPassword] once the swap went through, as it was otherwise. */
    internal fun endChange(newPassword: CharArray?) {
        synchronized(guard) {
            if (newPassword != null) {
                generation++ // keys taken before the change never write with the old password again
                // Locked meanwhile (a logout): it stays locked.
                password?.let {
                    it.fill('\u0000')
                    password = newPassword.copyOf()
                }
            }
            changing = false
        }
        if (newPassword != null) cryptoManager.clearKeyCache()
    }

    private fun schedule(deadline: Long) {
        val wait = deadline - now()
        if (wait <= 0) {
            lock() // "At once"
            return
        }
        synchronized(guard) {
            timer?.cancel()
            timer = appScope.launch {
                delay(wait)
                expireIfDue()
            }
        }
    }

    private fun expireIfDue() {
        val due = synchronized(guard) { password != null && autoLock.expired(now()) }
        if (due) lock()
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
