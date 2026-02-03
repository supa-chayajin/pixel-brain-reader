package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.StatsDashboardState
import cloud.wafflecommons.pixelbrainreader.data.usecase.GetLifeStatsDashboardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LifeStatsViewModel @Inject constructor(
    private val getLifeStatsDashboardUseCase: GetLifeStatsDashboardUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsDashboardState())
    val uiState: StateFlow<StatsDashboardState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val stats = getLifeStatsDashboardUseCase()
                _uiState.value = stats
            } catch (e: Exception) {
                // Log error
                e.printStackTrace()
            }
        }
    }
}
