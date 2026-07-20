package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [MoodTagSettingsScreen]. Thin wrapper over [MoodTagRepository] — the vault file
 * is the source of truth, so the ViewModel only forwards edits and re-exposes the flow.
 */
@HiltViewModel
class MoodTagSettingsViewModel @Inject constructor(
    private val repository: MoodTagRepository
) : ViewModel() {

    val tags: StateFlow<List<String>> = repository.tags

    init {
        viewModelScope.launch { repository.ensureLoaded() }
    }

    fun addTag(tag: String) {
        viewModelScope.launch { repository.addTag(tag) }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch { repository.removeTag(tag) }
    }

    fun moveTag(from: Int, to: Int) {
        viewModelScope.launch { repository.moveTag(from, to) }
    }
}
