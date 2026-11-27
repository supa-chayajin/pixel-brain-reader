package cloud.wafflecommons.pixelbrainreader.ui.games

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSyncSummary
import cloud.wafflecommons.pixelbrainreader.data.workers.GamesSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The code Connect shows; the device code itself stays in XboxAccount's call. */
data class PendingCode(val userCode: String, val expiresAt: Long)

data class XboxSettingsUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val status: XboxStatus? = null,
    /** 90 days after the last renewal: the sign-in ends if no sync runs before. */
    val validUntil: Long? = null,
    val busy: Boolean = false,
    /** The last action's result, in words. */
    val message: String? = null,
    /** The code to type at microsoft.com/link, while Connect waits for the sign-in. */
    val code: PendingCode? = null,
    val readOnly: Boolean = VaultWriteGate.readOnly
)

/**
 * Settings › Integrations › Xbox (13.3, XB-E): Connect by device code (the owner's own Microsoft app,
 * XB1), the first sync as the baseline, Sync now, Disconnect, and the last sync's line. Nothing Xbox
 * runs on a read-only device.
 */
@HiltViewModel
class XboxSettingsViewModel @Inject constructor(
    private val account: XboxAccount,
    private val xboxSync: XboxSync,
    private val scheduler: GamesSyncScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(XboxSettingsUiState(configured = account.configured))
    val state: StateFlow<XboxSettingsUiState> = _state.asStateFlow()

    /** Connect's one job: the code, the wait for the sign-in, the schedule, the first sync. */
    private var connecting: Job? = null

    init {
        viewModelScope.launch { account.status.collect { status -> _state.update { it.copy(status = status) } } }
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

    /** Step 1, the code; step 2, the wait while the owner types it at microsoft.com/link; then the baseline. */
    fun connect() {
        if (connecting?.isActive == true || _state.value.busy) return
        if (VaultWriteGate.refuses()) {
            _state.update { it.copy(readOnly = true, code = null, message = READ_ONLY_MESSAGE) }
            return
        }
        connecting = launchAction {
            _state.update { it.copy(busy = true, message = null, code = null) }
            val code = account.startSignIn().getOrElse { e ->
                _state.update { it.copy(busy = false, message = e.message) }
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
            _state.update { it.copy(code = null) }
            signedIn.onFailure { e ->
                _state.update { it.copy(busy = false, message = e.message) }
                refresh()
                return@launchAction
            }
            scheduler.update()
            // The first sync records the baseline: sessions start from the next one.
            val first = xboxSync.sync()
            _state.update {
                it.copy(
                    busy = false,
                    message = first.fold(
                        { s -> "Connected: ${s.titles} games found. Play time counts from now on." + createdPagesNote(s.pagesCreated) },
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
        _state.update { it.copy(busy = false, code = null) }
        refresh()
    }

    fun syncNow() {
        if (_state.value.busy) return
        launchAction {
            _state.update { it.copy(busy = true, message = null) }
            val result = xboxSync.sync()
            _state.update { it.copy(busy = false, message = result.fold({ s -> syncedLine(s) }, { e -> e.message })) }
            refresh()
        }
    }

    /** Forgets the sign-in; the nightly slots stay while PlayStation is connected. */
    fun disconnect() {
        if (_state.value.busy) return
        launchAction {
            account.disconnect()
            scheduler.update()
            _state.update { it.copy(message = "Disconnected. Games works by hand.") }
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
            _state.update { it.copy(busy = false, code = null, message = "Something went wrong (${e.javaClass.simpleName}): try again.") }
        }
    }

    private fun syncedLine(s: XboxSyncSummary): String =
        "Synced ${s.titles} games: ${s.sessions} new sessions, ${s.achievementSets} achievement lists updated." + createdPagesNote(s.pagesCreated)

    private companion object {
        const val TAG = "XboxSettings"

        /** Microsoft's refresh token lasts 90 days, renewed at each use (each sync). */
        const val REFRESH_LIFETIME_MS = 90L * 86_400_000L

        const val READ_ONLY_MESSAGE = "Read-only device: connect Xbox on your phone."
    }
}

/** What a sync's page creation adds to its message (XB4): nothing when it made no page. */
internal fun createdPagesNote(count: Int): String = when {
    count <= 0 -> ""
    count == 1 -> " 1 game page created."
    else -> " $count game pages created."
}
