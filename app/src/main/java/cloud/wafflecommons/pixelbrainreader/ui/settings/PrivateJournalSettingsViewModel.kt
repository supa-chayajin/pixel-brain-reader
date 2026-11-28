package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.privatevault.AutoLock
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Privacy & safety › Private journal (13.4, S-h): the journal's auto-lock (also in the
 * journal's menu) and whether its notes reach the AI (settled decision #20). Both are this device's
 * own settings.
 */
@HiltViewModel
class PrivateJournalSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository
) : ViewModel() {

    /** Minutes the journal stays open after it leaves the screen: one of [AutoLock.CHOICES]. */
    val autoLockMinutes: StateFlow<Int> = preferences.privateAutoLockMinutes
        .map { AutoLock.minutesOrDefault(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoLock.DEFAULT_MINUTES)

    val privateNotesInAi: StateFlow<Boolean> = preferences.privateNotesInAi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoLock(minutes: Int) {
        if (minutes !in AutoLock.CHOICES) return
        viewModelScope.launch { preferences.setPrivateAutoLockMinutes(minutes) }
    }

    /**
     * The private vault in the AI, on or off (settled decision #20). Retrieval follows at once; an
     * indexing run follows too, so switching off drops the private vectors and switching on embeds
     * the private notes now rather than at the next manual run. No AI on a read-only device.
     */
    fun setPrivateNotesInAi(enabled: Boolean) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            preferences.setPrivateNotesInAi(enabled)
            // After any run in progress, which read the switch when it started.
            WorkManager.getInstance(context).enqueueUniqueWork(
                IndexingWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                IndexingWorker.request(tag = "manual_indexing")
            )
        }
    }
}
