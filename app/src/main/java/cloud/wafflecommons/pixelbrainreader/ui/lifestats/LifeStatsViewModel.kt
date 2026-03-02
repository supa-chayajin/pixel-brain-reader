package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.domain.gamification.ApplyHealthSynergyUseCase
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute
import com.patrykandpatrick.vico.core.entry.FloatEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class LifeStatsViewModel @Inject constructor(
    gamificationRepository: GamificationRepository,
    moodRepository: MoodRepository,
    habitRepository: HabitRepository,
    private val applyHealthSynergyUseCase: ApplyHealthSynergyUseCase,
    gamificationPreferences: cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
) : ViewModel() {

    init {
        viewModelScope.launch {
            applyHealthSynergyUseCase(LocalDate.now())
        }
    }

    val isHealthSynergyActive: StateFlow<Boolean> = gamificationPreferences.lastHealthSynergyAppliedDateFlow
        .map { it == LocalDate.now().toString() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val rpgStats: StateFlow<Map<RpgAttribute, Float>> = gamificationRepository.gamificationState
        .map { state ->
            val statsMap = mutableMapOf<RpgAttribute, Float>()
            val maxLevel = 100f // Assume max level is 100 for normalization
            state.attributes.forEach { (gamificationAttr, value) ->
                val rpgAttr = when(gamificationAttr) {
                    cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.VIG -> cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute.VIGOR
                    cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.INT -> cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute.INTELLIGENCE
                    cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.FTH -> cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute.FAITH
                    cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.END -> cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute.ENDURANCE
                    cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.MND -> cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute.MIND
                    else -> null
                }
                if (rpgAttr != null) {
                    statsMap[rpgAttr] = (value.toFloat() / maxLevel).coerceIn(0f, 1f)
                }
            }
            statsMap
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val moodHistory: StateFlow<List<FloatEntry>> = moodRepository.getMoodFlow()
        .map { moods ->
            moods.sortedBy { it.date }.takeLast(7).mapIndexed { index, mood ->
                FloatEntry(x = index.toFloat(), y = mood.score.toFloat())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val habitCompletionRates: StateFlow<Float> = habitRepository.getLogsForYearFlow(LocalDate.now().year)
        .map { logsMap ->
            var totalLogs = 0
            var completedLogs = 0
            logsMap.values.flatten().forEach { log ->
                val target = log.value
                if (target > 0) completedLogs++
                totalLogs++
            }
            if (totalLogs == 0) 0f else (completedLogs.toFloat() / totalLogs.toFloat())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
}
