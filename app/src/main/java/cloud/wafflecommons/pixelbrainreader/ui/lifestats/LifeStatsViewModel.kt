package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.RpgCharacter
import cloud.wafflecommons.pixelbrainreader.data.repository.LifeStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

@HiltViewModel
class LifeStatsViewModel @Inject constructor(
    private val lifeStatsRepository: LifeStatsRepository,
    private val moodRepository: MoodRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LifeStatsUiState())
    val uiState: StateFlow<LifeStatsUiState> = _uiState.asStateFlow()

    private val _currentMonth = MutableStateFlow(YearMonth.now())

    init {
        loadStats()
        observeMoods()
    }

    private fun loadStats() {
        viewModelScope.launch {
            lifeStatsRepository.getCharacterStats()
                .catch { e -> 
                    _uiState.update { it.copy(isLoading = false) } // Handle error
                }
                .collect { character ->
                    _uiState.update { 
                        it.copy(character = character, isLoading = false) 
                    }
                }
        }
    }
    
    private fun observeMoods() {
        viewModelScope.launch {
            combine(
                moodRepository.moods,
                _currentMonth
            ) { allMoods, month ->
                // Filter for current month and map to Emoji logic
                val start = month.atDay(1)
                val end = month.atEndOfMonth()
                
                allMoods.filterKeys { date ->
                    !date.isBefore(start) && !date.isAfter(end)
                }.mapValues { (_, data) ->
                    // Use the summary logic from Repo or custom mapping
                    val avg = data.summary.averageScore
                    calculateEmoji(avg)
                }
            }.collect { monthlyMoods ->
                _uiState.update { it.copy(
                    monthlyMoods = monthlyMoods,
                    currentMonth = _currentMonth.value
                ) }
            }
        }
    }

    fun onNextMonth() {
        _currentMonth.update { it.plusMonths(1) }
    }

    fun onPrevMonth() {
        _currentMonth.update { it.minusMonths(1) }
    }
    
    // Custom mapping as requested
    private fun calculateEmoji(score: Double): String {
        val rounded = score.roundToInt()
        return when (rounded) {
             1 -> "😭"
             2 -> "☹️"
             3 -> "😐"
             4 -> "🙂"
             5 -> "🤩"
             else -> "😐"
        }
    }
}

data class LifeStatsUiState(
    val character: RpgCharacter? = null,
    val isLoading: Boolean = true,
    val currentMonth: YearMonth = YearMonth.now(),
    val monthlyMoods: Map<LocalDate, String> = emptyMap()
)
