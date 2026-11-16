package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the navigation shows beyond the tabs (13.2): the Games tab's live dot and the rail's names. */
@HiltViewModel
class NavViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    sessions: GameSessionController
) : ViewModel() {

    /** A wide window's rail shows the tabs' names beside their icons (kept on the device). */
    val railExpanded: StateFlow<Boolean> = preferences.navRailExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** A game session is running: the Games tab's live dot. */
    val gameRunning: StateFlow<Boolean> = sessions.running
        .map { it != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setRailExpanded(expanded: Boolean) {
        viewModelScope.launch { preferences.setNavRailExpanded(expanded) }
    }
}
