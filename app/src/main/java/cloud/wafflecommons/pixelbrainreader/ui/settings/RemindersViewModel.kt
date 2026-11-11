package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val userPrefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler,
    private val meetingReminderScheduler: MeetingReminderScheduler,
    modesRepository: ModesRepository
) : ViewModel() {

    /** Meeting prep notifications (12.12, D4): off until switched on. */
    val meetingPrepEnabled: StateFlow<Boolean> =
        userPrefs.meetingPrepReminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** The owner's name for Work mode, for the copy. */
    val workLabel: StateFlow<String> = modesRepository.visibility.map { it.workLabel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), modesRepository.visibility.value.workLabel)

    /** Today's meetings still to come get their notification at once, or every one is cancelled. */
    fun setMeetingPrepEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setMeetingPrepReminders(enabled)
            meetingReminderScheduler.reschedule()
        }
    }

    /** A notification when the weekly review or a monthly report is written (12.13): off until switched on. */
    val reviewNotificationsEnabled: StateFlow<Boolean> =
        userPrefs.reviewNotifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Nothing to schedule: the nightly burn writes the notes and posts when this is on. */
    fun setReviewNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setReviewNotifications(enabled) }
    }

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

    val checkInEnabled: StateFlow<Boolean> =
        userPrefs.eveningCheckInEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val checkInTime: StateFlow<String> =
        userPrefs.eveningCheckInTime.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "21:00")

    fun setCheckInEnabled(enabled: Boolean) = update { userPrefs.setEveningCheckInEnabled(enabled) }
    fun setCheckInTime(hhmm: String) = update { userPrefs.setEveningCheckInTime(hhmm) }

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
