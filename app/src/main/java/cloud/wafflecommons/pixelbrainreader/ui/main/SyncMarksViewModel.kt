package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncDigest
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncDigestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The last sync's digest for the file list's marks and a note's history (K3, 12.20; the owner's
 * D4): the notes it brought or changed, and HEAD before and after it.
 */
@HiltViewModel
class SyncMarksViewModel @Inject constructor(
    private val digests: SyncDigestRepository
) : ViewModel() {
    val lastSync: StateFlow<SyncDigest?> = digests.digest

    init {
        viewModelScope.launch { digests.load() }
    }
}
