package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val userPrefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    val vaultEnabled: StateFlow<Boolean> =
        userPrefs.vaultReminderEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val vaultTime: StateFlow<String> =
        userPrefs.vaultReminderTime.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "20:00")
    val choresEnabled: StateFlow<Boolean> =
        userPrefs.choresReminderEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val choresWindows: StateFlow<List<String>> =
        userPrefs.choresReminderWindows.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("09:00", "14:00", "20:00")
        )

    fun setVaultEnabled(enabled: Boolean) = update { userPrefs.setVaultReminderEnabled(enabled) }
    fun setVaultTime(hhmm: String) = update { userPrefs.setVaultReminderTime(hhmm) }
    fun setChoresEnabled(enabled: Boolean) = update { userPrefs.setChoresReminderEnabled(enabled) }

    fun addChoresWindow(hhmm: String) = update {
        userPrefs.setChoresReminderWindows(userPrefs.choresReminderWindows.first() + hhmm)
    }

    fun removeChoresWindow(hhmm: String) = update {
        userPrefs.setChoresReminderWindows(userPrefs.choresReminderWindows.first() - hhmm)
    }

    /** Persist the change, then force a reschedule so WorkManager reflects the new time. */
    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            reminderScheduler.reschedule(force = true)
        }
    }
}
