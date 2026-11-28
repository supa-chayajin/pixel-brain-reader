package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSync
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSyncSummary
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.workers.GamesSyncScheduler
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PsnSettingsUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val status: PsnStatus? = null,
    val expiresInDays: Long? = null,
    /** The page's own action runs: a sign-in, its first sync, Sync now. */
    val working: Boolean = false,
    /** A PlayStation sync runs, wherever it started (13.4: the nightly one, or one this page started before Back). */
    val syncing: Boolean = false,
    /** The last action's result, in words. */
    val message: String? = null,
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** AppClock's day (13.4): "Last sync" says its day from it, and follows midnight. */
    val today: LocalDate
) {
    /** The page waits: its own action, or a sync, so a page reopened during a sync says "Syncing…". */
    val busy: Boolean get() = working || syncing
}

/**
 * Settings › Connections › Games › PlayStation (RFC-009 §7). The nightly slots are the Games sync's (13.3): kept
 * while PlayStation or Xbox is connected, so a PSN disconnect leaves Xbox's in place.
 * 13.4 (the audit's settings-sync-cancelled-by-back): the first sync and Sync now run in the app's
 * scope, so Back only stops this page waiting for them; the sync itself (and the schedule set
 * before it) runs to its end, and the page shows it while it runs ([PsnSync.syncing]). Game night
 * and its Do Not Disturb access are on the Games page since 13.4 (S8).
 */
@HiltViewModel
class PsnSettingsViewModel @Inject constructor(
    private val account: PsnAccount,
    private val psnSync: PsnSync,
    private val scheduler: GamesSyncScheduler,
    private val clock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow(PsnSettingsUiState(configured = account.configured, today = clock.today.value))
    val state: StateFlow<PsnSettingsUiState> = _state.asStateFlow()

    /** What the owner pastes: kept in the screen only until Connect, never saved. */
    var pasted by mutableStateOf("")

    init {
        viewModelScope.launch { account.status.collect { status -> _state.update { it.copy(status = status) } } }
        // A sync's end reads the sign-in again: a renewal moves its expiry.
        viewModelScope.launch {
            var was = false
            psnSync.syncing.collect { syncing ->
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
            val tokens = account.tokens()
            _state.update {
                it.copy(
                    connected = tokens != null,
                    expiresInDays = tokens?.let { t -> (t.refreshExpiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 86_400_000L },
                    readOnly = VaultWriteGate.readOnly
                )
            }
        }
    }

    /**
     * Signs in with the pasted NPSSO, then the first sync: a baseline for a new sign-in (play counts
     * from now on), while a new NPSSO over an expired sign-in still kept keeps the play since the
     * last sync.
     */
    fun connect() {
        val input = pasted
        if (input.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            // Read before the sign-in saves its tokens: none kept means a first Connect, or one after Disconnect.
            val baseline = !account.isConnected()
            val result = account.connect(input)
            pasted = ""
            result.onSuccess {
                // Signed in: the page says so at once, while the first sync reads the games.
                _state.update { it.copy(connected = true, message = "Signed in. The first sync is reading your PlayStation games…") }
                // The schedule and the first sync outside the page: Back ends only this wait.
                val first = appScope.async {
                    scheduler.update()
                    psnSync.sync(baseline = baseline)
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
            }.onFailure { e -> _state.update { it.copy(working = false, message = e.message) } }
            refresh()
        }
    }

    fun syncNow() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            // Outside the page, as the first sync: Back ends only this wait.
            val result = appScope.async { psnSync.sync() }.await()
            _state.update { it.copy(working = false, message = result.fold({ s -> syncedLine(s) }, { e -> e.message })) }
            refresh()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            account.disconnect()
            // The slots stay while Xbox is connected; they go once neither console is.
            scheduler.update()
            _state.update { it.copy(message = "Disconnected: PlayStation no longer syncs.") }
            refresh()
        }
    }

    private fun syncedLine(s: PsnSyncSummary): String =
        "Synced ${s.titles} games: ${s.sessions} new sessions, ${s.trophySets} trophy lists updated." + createdPagesNote(s.pagesCreated)
}
