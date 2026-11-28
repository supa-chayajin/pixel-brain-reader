package cloud.wafflecommons.pixelbrainreader.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the navigation shows beyond the tabs (13.2): the Games tab's live dot, the Feeds tab's dot
 * (13.4) and the rail's names.
 */
@HiltViewModel
class NavViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    sessions: GameSessionController,
    watch: WatchRepository,
    modes: ModesRepository
) : ViewModel() {

    /** A wide window's rail shows the tabs' names beside their icons (kept on the device). */
    val railExpanded: StateFlow<Boolean> = preferences.navRailExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** A game session is running: the Games tab's live dot. */
    val gameRunning: StateFlow<Boolean> = sessions.running
        .map { it != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 13.4 (F1): headlines of the mode's feeds arrived since the Feeds tab was last opened, still
     * unread: the tab's dot. A cache that can't be read shows none.
     */
    val feedsNew: StateFlow<Boolean> = watch.newSinceOpened(modes.visibility)
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "The feeds' dot couldn't be read: ${e.javaClass.simpleName}")
            emit(false)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setRailExpanded(expanded: Boolean) {
        viewModelScope.launch { preferences.setNavRailExpanded(expanded) }
    }

    private companion object {
        const val TAG = "Nav"
    }
}
