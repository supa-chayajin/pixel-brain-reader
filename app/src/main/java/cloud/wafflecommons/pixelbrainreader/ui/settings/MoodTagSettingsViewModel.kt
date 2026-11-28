package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagCategories
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Moods page ([MoodTagSettingsScreen], 13.4, S10): the faces of this device's moods and
 * the mood tags. The tags live in the vault ([MoodTagRepository], the source of truth): every change
 * to them is refused on a read-only device. The face set is this device's own and stays usable there.
 */
@HiltViewModel
class MoodTagSettingsViewModel @Inject constructor(
    private val repository: MoodTagRepository,
    private val preferences: GamificationPreferences,
    private val widgetUpdateManager: WidgetUpdateManager
) : ViewModel() {

    val tags: StateFlow<List<MoodTag>> = repository.tags

    /** The faces every mood on this device wears (the check-in, the history, the stats, the widgets). */
    val moodEmojiSet: StateFlow<MoodEmojiSet> = preferences.moodEmojiSetFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoodEmojiSets.DEFAULT)

    init {
        viewModelScope.launch { repository.ensureLoaded() }
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

    /** [icon]: the icon picked for the new tag, null for its default; [category]: its category key (12.19, M3). */
    fun addTag(tag: String, icon: String?, category: String?) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch { repository.addTag(tag, icon, category) }
    }

    fun removeTag(tag: String) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch { repository.removeTag(tag) }
    }

    fun moveTag(from: Int, to: Int) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch { repository.moveTag(from, to) }
    }

    /** null brings back the tag's default icon. */
    fun setIcon(tag: String, icon: String?) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch { repository.setIcon(tag, icon) }
    }

    fun setCategory(tag: String, category: MoodTagCategory) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch { repository.setCategory(tag, category.key) }
    }

    /** Up or down past its neighbour in the same category; nothing at that end of it (the screen reader's moves). */
    fun moveWithinCategory(tag: String, up: Boolean) {
        if (VaultWriteGate.refuses()) return
        val (from, to) = MoodTagCategories.moveWithinCategory(tags.value, tag, up) ?: return
        viewModelScope.launch { repository.moveTag(from, to) }
    }

    /** To place [targetInCategory] among its category's tags (13.4, S5): a drag's drop. */
    fun moveWithinCategoryTo(tag: String, targetInCategory: Int) {
        if (VaultWriteGate.refuses()) return
        val (from, to) = MoodTagCategories.moveWithinCategoryTo(tags.value, tag, targetInCategory) ?: return
        viewModelScope.launch { repository.moveTag(from, to) }
    }
}
