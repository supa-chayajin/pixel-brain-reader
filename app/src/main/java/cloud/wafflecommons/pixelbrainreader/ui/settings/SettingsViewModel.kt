package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultGraphAnalyzer
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultGraphReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Settings home (13.4, S-b): its two device switches, the owner's names for the modes and the
 * toolkit, and the vault's link check. Every other control lives on its page, with its own ViewModel.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val modes: ModesRepository,
    private val vaultGraphAnalyzer: VaultGraphAnalyzer
) : ViewModel() {

    /** The home's switches (both device settings, usable on a read-only device) and the names it shows. */
    data class SettingsUiState(
        val soundEffects: Boolean = false,
        val screenShareSafe: Boolean = false,
        val appearance: ModeAppearance = ModeAppearance()
    )

    val uiState: StateFlow<SettingsUiState> = combine(preferences.soundEffectsEnabled, modes.visibility) { sound, visibility ->
        SettingsUiState(soundEffects = sound, screenShareSafe = visibility.screenShareSafe, appearance = visibility.appearance)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        modes.visibility.value.let { SettingsUiState(screenShareSafe = it.screenShareSafe, appearance = it.appearance) }
    )

    private val _vaultGraphReport = MutableStateFlow<VaultGraphReport?>(null)
    val vaultGraphReport: StateFlow<VaultGraphReport?> = _vaultGraphReport.asStateFlow()

    private val _isAnalyzingGraph = MutableStateFlow(false)
    val isAnalyzingGraph: StateFlow<Boolean> = _isAnalyzingGraph.asStateFlow()

    fun setSoundEffects(enabled: Boolean) {
        viewModelScope.launch { preferences.setSoundEffectsEnabled(enabled) }
    }

    /** Kept on the device by the modes: the widgets rebuild with it. */
    fun setScreenShareSafe(enabled: Boolean) {
        viewModelScope.launch { modes.setScreenShareSafe(enabled) }
    }

    /** Vault links: dead links and orphan notes, read from Room on this phone. */
    fun analyzeVaultGraph() {
        if (_isAnalyzingGraph.value) return
        viewModelScope.launch {
            _isAnalyzingGraph.value = true
            try {
                _vaultGraphReport.value = vaultGraphAnalyzer.analyze()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exception's type only: its message can hold a note's path.
                Log.e(TAG, "The vault check failed: ${e.javaClass.simpleName}")
                if (BuildConfig.DEBUG) Log.d(TAG, "The vault check failed", e)
            } finally {
                _isAnalyzingGraph.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
