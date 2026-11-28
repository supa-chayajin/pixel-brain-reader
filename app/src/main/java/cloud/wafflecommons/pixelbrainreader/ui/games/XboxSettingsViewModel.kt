package cloud.wafflecommons.pixelbrainreader.ui.games

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSyncSummary
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.workers.GamesSyncScheduler
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/** The code Connect shows; the device code itself stays in XboxAccount's call. */
data class PendingCode(val userCode: String, val expiresAt: Long)

data class XboxSettingsUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val status: XboxStatus? = null,
    /** 90 days after the last renewal: the sign-in ends if no sync runs before. */
    val validUntil: Long? = null,
    /** The page's own action runs: the code's wait, the sign-in, its first sync, Sync now, Disconnect. */
    val working: Boolean = false,
    /** An Xbox sync runs, wherever it started (13.4: the nightly one, or one this page started before Back). */
    val syncing: Boolean = false,
    /** The last action's result, in words. */
    val message: String? = null,
    /** The code to type at microsoft.com/link, while Connect waits for the sign-in. */
    val code: PendingCode? = null,
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** AppClock's day (13.4): "Last sync" says its day from it, and follows midnight. */
    val today: LocalDate
) {
    /** The page waits: its own action, or a sync, so a page reopened during a sync says "Syncing…". */
    val busy: Boolean get() = working || syncing
}

/**
 * Settings › Integrations › Xbox (13.3, XB-E): Connect by device code (the owner's own Microsoft app,
 * XB1), a new sign-in's first sync as the baseline, Sync now, Disconnect, and the last sync's line.
 * Nothing Xbox runs on a read-only device. 13.4 (the audit's settings-sync-cancelled-by-back): the
 * first sync and Sync now run in the app's scope, so Back only stops this page waiting for them;
 * the sync itself (and the schedule set before it) runs to its end, and the page shows it while it
 * runs ([XboxSync.syncing]).
 */
@HiltViewModel
class XboxSettingsViewModel @Inject constructor(
    private val account: XboxAccount,
    private val xboxSync: XboxSync,
    private val scheduler: GamesSyncScheduler,
    private val clock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow(XboxSettingsUiState(configured = account.configured, today = clock.today.value))
    val state: StateFlow<XboxSettingsUiState> = _state.asStateFlow()

    /** Connect's one job: the code, the wait for the sign-in, the schedule, the first sync. */
    private var connecting: Job? = null

    init {
        viewModelScope.launch { account.status.collect { status -> _state.update { it.copy(status = status) } } }
        // A sync's end reads the sign-in again: each sync renews it.
        viewModelScope.launch {
            var was = false
            xboxSync.syncing.collect { syncing ->
                _state.update { it.copy(syncing = syncing) }
                if (was && !syncing) refresh()
                was = syncing
            }
        }
        viewModelScope.launch { clock.today.collect { day -> _state.update { it.copy(today = day) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val tokens = if (account.configured) account.tokens() else null
                _state.update {
                    it.copy(
                        configured = account.configured,
                        connected = tokens != null,
                        validUntil = tokens?.refreshIssuedAt?.takeIf { at -> at > 0 }?.plus(REFRESH_LIFETIME_MS),
                        readOnly = VaultWriteGate.readOnly
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The sign-in couldn't be read (the phone's key store): the page keeps what it shows,
                // a code being waited on included.
                Log.w(TAG, "Xbox settings: the sign-in couldn't be read (${e.javaClass.simpleName})")
            }
        }
    }

    /**
     * Step 1, the code; step 2, the wait while the owner types it at microsoft.com/link; then the first
     * sync: a baseline for a new sign-in (play counts from now on), while Sign in again, over an expired
     * sign-in still kept, keeps the play since the last sync.
     */
    fun connect() {
        if (connecting?.isActive == true || _state.value.busy) return
        if (VaultWriteGate.refuses()) {
            _state.update { it.copy(readOnly = true, code = null, message = READ_ONLY_MESSAGE) }
            return
        }
        connecting = launchAction {
            _state.update { it.copy(working = true, message = null, code = null) }
            // Read before the sign-in saves its tokens: none kept means a first Connect, or one after Disconnect.
            val baseline = !account.isConnected()
            val code = account.startSignIn().getOrElse { e ->
                _state.update { it.copy(working = false, message = e.message) }
                return@launchAction
            }
            _state.update { it.copy(code = PendingCode(code.userCode, code.expiresAt)) }
            val signedIn = try {
                account.awaitSignIn(code)
            } catch (e: CancellationException) {
                // Cancelled just as the sign-in finished, its tokens maybe saved: the schedule follows them.
                withContext(NonCancellable) { if (account.isConnected()) scheduler.update() }
                throw e
            }
            signedIn.onFailure { e ->
                _state.update { it.copy(code = null, working = false, message = e.message) }
                refresh()
                return@launchAction
            }
            // Signed in: the page says so at once, while the first sync reads the games (tens of seconds).
            _state.update { it.copy(code = null, connected = true, message = SIGNED_IN_MESSAGE) }
            // The schedule and the first sync outside the page: Back ends only this wait.
            val first = appScope.async {
                scheduler.update()
                xboxSync.sync(baseline = baseline)
            }.await()
            _state.update {
                it.copy(
                    working = false,
                    message = first.fold(
                        { s ->
                            if (baseline) {
                                "Connected: ${s.titles} games found. Play time counts from now on." + createdPagesNote(s.pagesCreated)
                            } else {
                                "Signed in again. " + syncedLine(s)
                            }
                        },
                        { e -> "Connected, but the first sync failed: ${e.message}" }
                    )
                )
            }
            refresh()
        }
    }

    /** Stops the wait for the sign-in (only while the code shows: once signed in, the first sync runs to its end). */
    fun cancelConnect() {
        if (_state.value.code == null) return
        connecting?.cancel()
        connecting = null
        _state.update { it.copy(working = false, code = null) }
        refresh()
    }

    fun syncNow() {
        if (_state.value.busy) return
        launchAction {
            _state.update { it.copy(working = true, message = null) }
            // Outside the page, as the first sync: Back ends only this wait.
            val result = appScope.async { xboxSync.sync() }.await()
            _state.update { it.copy(working = false, message = result.fold({ s -> syncedLine(s) }, { e -> e.message })) }
            refresh()
        }
    }

    /** Forgets the sign-in; the nightly slots stay while PlayStation is connected. */
    fun disconnect() {
        if (_state.value.busy) return
        launchAction {
            account.disconnect()
            scheduler.update()
            _state.update { it.copy(message = "Disconnected: Xbox no longer syncs.") }
            refresh()
        }
    }

    /** [block] in the ViewModel's scope; an unexpected failure (the phone's key store, say) ends it in words, never a crash. */
    private fun launchAction(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The type only: a message could carry an address.
            Log.w(TAG, "Xbox settings: ${e.javaClass.simpleName}")
            _state.update { it.copy(working = false, code = null, message = "Something went wrong (${e.javaClass.simpleName}): try again.") }
        }
    }

    private fun syncedLine(s: XboxSyncSummary): String =
        "Synced ${s.titles} games: ${s.sessions} new sessions, ${s.achievementSets} achievement lists updated." + createdPagesNote(s.pagesCreated)

    private companion object {
        const val TAG = "XboxSettings"

        /** Microsoft's refresh token lasts 90 days, renewed at each use (each sync). */
        const val REFRESH_LIFETIME_MS = 90L * 86_400_000L

        const val READ_ONLY_MESSAGE = "Read-only device: connect Xbox on your phone."

        /** Once Microsoft answered, while the first sync runs. */
        const val SIGNED_IN_MESSAGE = "Signed in. The first sync is reading your Xbox games…"
    }
}

/** What a sync's page creation adds to its message (XB4): nothing when it made no page. */
internal fun createdPagesNote(count: Int): String = when {
    count <= 0 -> ""
    count == 1 -> " 1 game page created."
    else -> " $count game pages created."
}
