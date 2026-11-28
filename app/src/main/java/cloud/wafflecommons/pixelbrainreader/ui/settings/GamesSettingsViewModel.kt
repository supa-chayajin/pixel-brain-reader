package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GamePlatform
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The Games page's state (13.4, S8): each console's sign-in and last sync, Game night's access, the tile's unlock. */
data class GamesSettingsState(
    val psnConfigured: Boolean = BuildConfig.PSN_CLIENT_ID.isNotBlank(),
    val psnConnected: Boolean = false,
    val psnStatus: PsnStatus? = null,
    val xboxConfigured: Boolean = BuildConfig.XBOX_CLIENT_ID.isNotBlank(),
    val xboxConnected: Boolean = false,
    val xboxStatus: XboxStatus? = null,
    val dndGranted: Boolean = false,
    val tileUnlock: Boolean = true,
    val personalLabel: String = ModeAppearance().label(ContextMode.PERSONAL),
    val today: LocalDate,
    val readOnly: Boolean = VaultWriteGate.readOnly
)

/**
 * Settings › Connections › Games (13.4, S8): PlayStation and Xbox (each its status, opening its page;
 * only in a build that carries its client), Game night and its Do Not Disturb access, and whether a
 * start from the Quick Settings tile on a locked phone asks for an unlock first (G2; on by default,
 * this device's own).
 */
@HiltViewModel
class GamesSettingsViewModel @Inject constructor(
    private val psn: PsnAccount,
    private val xbox: XboxAccount,
    private val platform: GamePlatform,
    private val preferences: UserPreferencesRepository,
    modes: ModesRepository,
    clock: AppClock
) : ViewModel() {

    private val _state = MutableStateFlow(
        GamesSettingsState(
            psnConfigured = psn.configured,
            xboxConfigured = xbox.configured,
            personalLabel = modes.visibility.value.appearance.label(ContextMode.PERSONAL),
            today = clock.today.value
        )
    )
    val state: StateFlow<GamesSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch { psn.status.collect { status -> _state.update { it.copy(psnStatus = status) } } }
        viewModelScope.launch { xbox.status.collect { status -> _state.update { it.copy(xboxStatus = status) } } }
        viewModelScope.launch { preferences.gameTileUnlock.collect { required -> _state.update { it.copy(tileUnlock = required) } } }
        viewModelScope.launch { clock.today.collect { day -> _state.update { it.copy(today = day) } } }
        viewModelScope.launch {
            modes.visibility.map { it.appearance.label(ContextMode.PERSONAL) }.distinctUntilChanged()
                .collect { label -> _state.update { it.copy(personalLabel = label) } }
        }
        refresh()
    }

    /** On resume: who is signed in, and whether Do Not Disturb access is granted (back from the system's page). */
    fun refresh() {
        viewModelScope.launch {
            try {
                val psnConnected = psn.isConnected()
                val xboxConnected = xbox.isConnected()
                _state.update {
                    it.copy(
                        psnConnected = psnConnected,
                        xboxConnected = xboxConnected,
                        dndGranted = platform.canGuard(),
                        readOnly = VaultWriteGate.readOnly
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The consoles' sign-ins couldn't be read: ${e.javaClass.simpleName}")
                _state.update { it.copy(dndGranted = platform.canGuard()) }
            }
        }
    }

    /** A device setting: the tile is unavailable on a read-only device, where the switch is off. */
    fun setTileUnlock(required: Boolean) {
        viewModelScope.launch { preferences.setGameTileUnlock(required) }
    }

    fun accessSettingsIntent(): Intent = platform.accessSettingsIntent()

    private companion object {
        const val TAG = "GamesSettings"
    }
}
