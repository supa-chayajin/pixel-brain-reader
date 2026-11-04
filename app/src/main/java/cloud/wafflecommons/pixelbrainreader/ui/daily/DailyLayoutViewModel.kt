package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Daily blocks' order, which ones are always shown, and whether the "More today" fold is open. */
@HiltViewModel
class DailyLayoutViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository
) : ViewModel() {

    val shown: StateFlow<Set<DailySection>> = preferences.dailyShownSections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailySection.DEFAULT_SHOWN)

    val order: StateFlow<List<DailySection>> = preferences.dailySectionOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailySection.entries.toList())

    val moreExpanded: StateFlow<Boolean> = preferences.dailyMoreExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setShown(section: DailySection, shown: Boolean) {
        viewModelScope.launch { preferences.setDailySectionShown(section, shown) }
    }

    /** One step [up] or down within the block's group (on screen, or in "More today"). */
    fun move(section: DailySection, up: Boolean) {
        viewModelScope.launch { preferences.moveDailySection(section, up) }
    }

    fun setMoreExpanded(expanded: Boolean) {
        viewModelScope.launch { preferences.setDailyMoreExpanded(expanded) }
    }
}
