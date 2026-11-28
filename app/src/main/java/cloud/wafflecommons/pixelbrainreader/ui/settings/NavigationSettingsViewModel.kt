package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeTabs
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Navigation (13.4, S3): the tabs' order (this device's, a drag's drop), the modes each
 * tab shows in (the vault's Modes file: refused on a read-only device) and the rail's names (this
 * device's).
 */
@HiltViewModel
class NavigationSettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val modes: ModesRepository
) : ViewModel() {

    /** The bar's order, every tab in it (a tab added by an update joins its end). */
    val order: StateFlow<List<String>> = preferences.navBarOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferencesRepository.DEFAULT_NAVBAR_ORDER)

    /** The modes' configuration: the tabs each mode hides, the modes' and the toolkit's names. */
    val config: StateFlow<ModesConfig> = modes.config

    val railExpanded: StateFlow<Boolean> = preferences.navRailExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Once, at a drag's drop. */
    fun setOrder(order: List<String>) {
        viewModelScope.launch { preferences.setNavBarOrder(order) }
    }

    /** [key] shown in the modes of [shown], hidden in the others: a vault write, refused on a read-only device. */
    fun setShownIn(key: String, shown: Set<ContextMode>) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            try {
                modes.updateConfig { ModeTabs.withShownIn(it, key, shown) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A tab's modes couldn't be saved: ${e.javaClass.simpleName}")
            }
        }
    }

    fun setRailExpanded(expanded: Boolean) {
        viewModelScope.launch { preferences.setNavRailExpanded(expanded) }
    }

    private companion object {
        const val TAG = "NavigationSettings"
    }
}
