package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.usecase.GetLifeStatsDashboardUseCase
import com.patrykandpatrick.vico.core.entry.FloatEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class LifeStatsUiState(
    // Sleep
    val sleepData: List<FloatEntry> = emptyList(),
    val sleepLabels: Map<Float, String> = emptyMap(),
    
    // Steps
    val stepData: List<FloatEntry> = emptyList(),
    val stepLabels: Map<Float, String> = emptyMap(),
    
    // Weekly Correlation
    val weeklyHrData: List<FloatEntry> = emptyList(),
    val weeklyMoodData: List<FloatEntry> = emptyList(),
    val weeklyLabels: Map<Float, String> = emptyMap(),
    
    // Today Correlation (Intraday)
    val todayHrData: List<FloatEntry> = emptyList(),
    val todayMoodData: List<FloatEntry> = emptyList(),
    // For intraday, labels might be hours "12:00", we can map hour index or similar if needed, 
    // but usually Intraday is just 0..24. Let's keep it simple or add if requested.
    // The previous code mapped timestamp to hour float (13.5 = 13:30).
    // The User request emphasizes mapped indices for avoiding precision issues. 
    // For Intraday, if we map to index 0..N, we lose the "Time of day" spacing if data is sparse.
    // However, to strictly follow "precision of x values is too large", we should map to index.
    // But for a timeline, sparse data mapped to 0,1,2 will look contiguous.
    // Let's stick to the user's explicit request: "Map your data to a simple index".
    // We will provide labels for the indices.
    val todayLabels: Map<Float, String> = emptyMap()
)

@HiltViewModel
class LifeStatsViewModel @Inject constructor(
    private val getLifeStatsDashboardUseCase: GetLifeStatsDashboardUseCase,
    private val uiEffectManager: cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LifeStatsUiState())
    val uiState: StateFlow<LifeStatsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stats = getLifeStatsDashboardUseCase()
                
                // 1. Sleep Transformation
                val sleepEntries = mutableListOf<FloatEntry>()
                val sleepLabels = mutableMapOf<Float, String>()
                stats.sleepHistory.forEachIndexed { index, metric ->
                    val x = index.toFloat()
                    sleepEntries.add(FloatEntry(x = x, y = metric.value.toFloat()))
                    sleepLabels[x] = metric.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // 2. Steps Transformation
                val stepEntries = mutableListOf<FloatEntry>()
                val stepLabels = mutableMapOf<Float, String>()
                stats.stepHistory.forEachIndexed { index, metric ->
                    val x = index.toFloat()
                    stepEntries.add(FloatEntry(x = x, y = metric.value.toFloat()))
                    stepLabels[x] = metric.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // 3. Weekly Correlation Transformation
                val weeklyHrEntries = mutableListOf<FloatEntry>()
                val weeklyMoodEntries = mutableListOf<FloatEntry>()
                val weeklyLabels = mutableMapOf<Float, String>()
                stats.weeklyCorrelation.forEachIndexed { index, point ->
                    val x = index.toFloat()
                    weeklyHrEntries.add(FloatEntry(x = x, y = point.avgBpm.toFloat()))
                    weeklyMoodEntries.add(FloatEntry(x = x, y = point.moodScore.toFloat() * 20f))
                    weeklyLabels[x] = point.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // 4. Today Transformation 
                // Note: Intraday points might be naturally strictly ordered. 
                // We map them to index 0..N to ensure safety. 
                // Label can be the time "HH:mm".
                val todayHrEntries = mutableListOf<FloatEntry>()
                val todayMoodEntries = mutableListOf<FloatEntry>()
                val todayLabels = mutableMapOf<Float, String>()
                
                // We need to iterate over the list once and handle both potential values.
                // Assuming the list is ordered by time.
                stats.todayCorrelation.forEachIndexed { index, point ->
                    val x = index.toFloat()
                    // Time label
                    val timeLabel = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(point.timestamp)
                    
                    if (point.bpm != null) {
                        todayHrEntries.add(FloatEntry(x = x, y = point.bpm.toFloat()))
                    }
                    if (point.moodScore != null) {
                        todayMoodEntries.add(FloatEntry(x = x, y = point.moodScore.toFloat() * 20f))
                    }
                    // Only add label if there is data? Or always?
                    if (point.bpm != null || point.moodScore != null) {
                         todayLabels[x] = timeLabel
                    }
                }

                // 5. Check Goals & Trigger Effects
                val lastSleep = stats.sleepHistory.lastOrNull()?.value ?: 0.0
                val lastSteps = stats.stepHistory.lastOrNull()?.value ?: 0
                
                if (lastSleep >= 7.0 || lastSteps >= 7000) {
                    // Trigger only if not already triggered? 
                    // For now, triggering on refresh might be annoying if it happens every time.
                    // Ideally check if "just achieved". 
                    // But assuming this screen is viewed to "check stats", a little celebration is fine.
                    // Using tryTriggerEffect to avoid spamming if multiple calls happen.
                     uiEffectManager.tryTriggerEffect(
                         cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect.Confetti(
                             cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType.GOAL_REACHED
                         )
                     )
                }

                _uiState.value = LifeStatsUiState(
                    sleepData = sleepEntries,
                    sleepLabels = sleepLabels,
                    stepData = stepEntries,
                    stepLabels = stepLabels,
                    weeklyHrData = weeklyHrEntries,
                    weeklyMoodData = weeklyMoodEntries,
                    weeklyLabels = weeklyLabels,
                    todayHrData = todayHrEntries,
                    todayMoodData = todayMoodEntries,
                    todayLabels = todayLabels
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
