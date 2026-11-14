package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GamePlatform
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSync
import cloud.wafflecommons.pixelbrainreader.data.workers.GamesSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PsnSettingsUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val status: PsnStatus? = null,
    val expiresInDays: Long? = null,
    val busy: Boolean = false,
    /** The last action's result, in words. */
    val message: String? = null,
    val dndGranted: Boolean = false,
    val readOnly: Boolean = VaultWriteGate.readOnly
)

/** Settings → Integrations → PlayStation Network (RFC-009 §7). */
@HiltViewModel
class PsnSettingsViewModel @Inject constructor(
    private val account: PsnAccount,
    private val psnSync: PsnSync,
    private val platform: GamePlatform,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val _state = MutableStateFlow(PsnSettingsUiState(configured = account.configured))
    val state: StateFlow<PsnSettingsUiState> = _state.asStateFlow()

    /** What the owner pastes: kept in the screen only until Connect, never saved. */
    var pasted by mutableStateOf("")

    init {
        viewModelScope.launch { account.status.collect { status -> _state.update { it.copy(status = status) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val tokens = account.tokens()
            _state.update {
                it.copy(
                    connected = tokens != null,
                    expiresInDays = tokens?.let { t -> (t.refreshExpiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 86_400_000L },
                    dndGranted = platform.canGuard(),
                    readOnly = VaultWriteGate.readOnly
                )
            }
        }
    }

    fun connect() {
        val input = pasted
        if (input.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = account.connect(input)
            pasted = ""
            result.onSuccess {
                GamesSyncWorker.ensureScheduled(workManager)
                // The first sync records the baseline: sessions start from the next one.
                val first = psnSync.sync()
                _state.update {
                    it.copy(
                        busy = false,
                        message = first.fold(
                            { s -> "Connected: ${s.titles} games found. Play time counts from now on." },
                            { e -> "Connected, but the first sync failed: ${e.message}" }
                        )
                    )
                }
            }.onFailure { e -> _state.update { it.copy(busy = false, message = e.message) } }
            refresh()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = psnSync.sync()
            _state.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        { s -> "Synced ${s.titles} games: ${s.sessions} new sessions, ${s.trophySets} trophy lists updated." },
                        { e -> e.message }
                    )
                )
            }
            refresh()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            account.disconnect()
            GamesSyncWorker.cancel(workManager)
            _state.update { it.copy(message = "Disconnected. Games works by hand.") }
            refresh()
        }
    }
}
