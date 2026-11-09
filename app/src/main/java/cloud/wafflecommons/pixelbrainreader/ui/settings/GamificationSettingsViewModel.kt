package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GamificationSettingsViewModel @Inject constructor(
    private val preferences: GamificationPreferences,
    private val widgetUpdateManager: WidgetUpdateManager
) : ViewModel() {

    val stepTarget: StateFlow<Int> = preferences.stepTargetFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 10000
        )

    val sleepMinMinutes: StateFlow<Int> = preferences.sleepMinMinutesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 300
        )

    val tagToStatMapping: StateFlow<Map<String, Attribute>> = preferences.tagToStatMappingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val moodEmojiSet: StateFlow<MoodEmojiSet> = preferences.moodEmojiSetFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MoodEmojiSets.DEFAULT
        )

    fun updateStepTarget(target: Int) {
        viewModelScope.launch {
            preferences.setStepTarget(target)
        }
    }

    fun updateSleepMinMinutes(minutes: Int) {
        viewModelScope.launch {
            preferences.setSleepMinMinutes(minutes)
        }
    }

    fun addTagMapping(tag: String, attribute: Attribute) {
        val currentMap = tagToStatMapping.value.toMutableMap()
        currentMap[tag] = attribute
        viewModelScope.launch {
            preferences.setTagToStatMapping(currentMap)
        }
    }

    fun removeTagMapping(tag: String) {
        val currentMap = tagToStatMapping.value.toMutableMap()
        currentMap.remove(tag)
        viewModelScope.launch {
            preferences.setTagToStatMapping(currentMap)
        }
    }

    /**
     * Saves the device's mood emoji set. The screens follow the preference; the widgets are redrawn
     * (the Mood widget reads the set live, the Companion widget's chart from a rebuilt snapshot).
     */
    fun selectMoodEmojiSet(id: String) {
        viewModelScope.launch {
            preferences.setMoodEmojiSet(id)
            widgetUpdateManager.triggerUpdate()
            // Not suspending: nothing to cancel. WorkManager unavailable → the next rebuild picks it up.
            runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
        }
    }
}
