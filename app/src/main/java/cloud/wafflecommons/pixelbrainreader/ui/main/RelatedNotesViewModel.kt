package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.RelatedNotes
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Related while writing (K5, 12.20): whether it shows, kept on the device, and the finder. */
@HiltViewModel
class RelatedNotesViewModel @Inject constructor(
    private val relatedNotes: RelatedNotes,
    private val preferences: UserPreferencesRepository
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.relatedWhileWriting
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { preferences.setRelatedWhileWriting(on) }
    }

    suspend fun closestTo(paragraph: String, notePath: String?): List<String> =
        relatedNotes.closestTo(paragraph, notePath)
}
