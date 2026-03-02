package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GamificationSettingsViewModel @Inject constructor(
    private val preferences: GamificationPreferences
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

    val moodEmojiMapping: StateFlow<Map<Int, String>> = preferences.moodEmojiMappingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = mapOf(1 to "😭", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "🤩")
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

    fun updateMoodEmojiMapping(score: Int, emoji: String) {
        val currentMap = moodEmojiMapping.value.toMutableMap()
        currentMap[score] = emoji
        viewModelScope.launch {
            preferences.setMoodEmojiMapping(currentMap)
        }
    }
}
