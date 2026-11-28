package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog

/**
 * Settings › Your day › Reminders: the six reminders, each a switch row (with its time, or its
 * windows, under it while it is on), each an anchor the search opens the page at (13.4, S6). One
 * time picker for all of them, the app's own. This device's settings; they don't run on a read-only
 * device, which says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    viewModel: RemindersViewModel = hiltViewModel()
) {
    val vaultEnabled by viewModel.vaultEnabled.collectAsStateWithLifecycle()
    val vaultTime by viewModel.vaultTime.collectAsStateWithLifecycle()
    val choresEnabled by viewModel.choresEnabled.collectAsStateWithLifecycle()
    val choresWindows by viewModel.choresWindows.collectAsStateWithLifecycle()
    val checkInEnabled by viewModel.checkInEnabled.collectAsStateWithLifecycle()
    val checkInTime by viewModel.checkInTime.collectAsStateWithLifecycle()
    val meetingPrepEnabled by viewModel.meetingPrepEnabled.collectAsStateWithLifecycle()
    val oneOnOneEnabled by viewModel.oneOnOneEnabled.collectAsStateWithLifecycle()
    val reviewNotificationsEnabled by viewModel.reviewNotificationsEnabled.collectAsStateWithLifecycle()
    val workLabel by viewModel.workLabel.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // When set, holds the initial "HH:mm" and the callback to run on confirm.
    var timePicker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    SettingsPageScaffold(page = SettingsPage.REMINDERS, onBack = onBack) {
        if (VaultWriteGate.readOnly) {
            SettingsGroup { row { SettingsNote("Reminders don't run on a read-only device.") } }
        }

        // --- Evening check-in ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Evening check-in",
                    checked = checkInEnabled,
                    onCheckedChange = viewModel::setCheckInEnabled,
                    description = "One minute from the notification: tap your mood, then type one line or something " +
                        "you're thankful for. No need to open the app.",
                    icon = Icons.Rounded.Mood,
                    anchor = SettingsAnchors.CHECK_IN
                )
            }
            if (checkInEnabled) {
                row {
                    TimeRow(label = "Check-in time", time = checkInTime) {
                        timePicker = checkInTime to { hhmm -> viewModel.setCheckInTime(hhmm) }
                    }
                }
            }
        }

        // --- Private vault reminder ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Private vault reminder",
                    checked = vaultEnabled,
                    onCheckedChange = viewModel::setVaultEnabled,
                    description = "A daily nudge to journal at your chosen time — always fires, never skipped.",
                    icon = Icons.Rounded.Lock,
                    anchor = SettingsAnchors.VAULT_REMINDER
                )
            }
            if (vaultEnabled) {
                row {
                    TimeRow(label = "Reminder time", time = vaultTime) {
                        timePicker = vaultTime to { hhmm -> viewModel.setVaultTime(hhmm) }
                    }
                }
            }
        }

        // --- Meeting prep (12.12) ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Meeting prep",
                    checked = meetingPrepEnabled,
                    onCheckedChange = viewModel::setMeetingPrepEnabled,
                    description = "Ten minutes before a meeting from your calendar: what your notes and the mission log " +
                        "hold about it. In $workLabel mode only; Do Not Disturb silences it.",
                    icon = Icons.Rounded.Groups,
                    anchor = SettingsAnchors.MEETING_PREP
                )
            }
        }

        // --- 1:1 reminders (12.19) ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "1:1 reminders",
                    checked = oneOnOneEnabled,
                    onCheckedChange = viewModel::setOneOnOneEnabled,
                    description = "Thirty minutes before a meeting you linked to a person in its prep, or one titled like " +
                        "a 1:1. A work person's in $workLabel mode only; Do Not Disturb silences it.",
                    icon = Icons.Rounded.Diversity3,
                    anchor = SettingsAnchors.ONE_ON_ONE
                )
            }
        }

        // --- Weekly review & monthly reports (12.13) ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Weekly review & monthly reports",
                    checked = reviewNotificationsEnabled,
                    onCheckedChange = viewModel::setReviewNotificationsEnabled,
                    description = "One quiet notification when Sunday night's review or last month's reports are " +
                        "written, opening them. The work report's only in $workLabel mode.",
                    icon = Icons.Rounded.EditCalendar,
                    anchor = SettingsAnchors.REVIEWS
                )
            }
        }

        // --- Chores & habits reminder ---
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Chores & habits reminder",
                    checked = choresEnabled,
                    onCheckedChange = viewModel::setChoresEnabled,
                    description = "At each window, a summary of due chores and unfinished habits.",
                    icon = Icons.Rounded.CleaningServices,
                    anchor = SettingsAnchors.CHORES
                )
            }
            if (choresEnabled) {
                row {
                    SettingsRow(
                        title = "Time windows",
                        description = if (choresWindows.isEmpty()) "No window yet" else null,
                        below = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                choresWindows.forEach { win ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            viewModel.removeChoresWindow(win)
                                        },
                                        label = { Text(win) },
                                        trailingIcon = {
                                            Icon(Icons.Rounded.Close, contentDescription = "Remove $win", modifier = Modifier.size(16.dp))
                                        }
                                    )
                                }
                            }
                            FilledTonalButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                timePicker = "09:00" to { hhmm -> viewModel.addChoresWindow(hhmm) }
                            }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add window")
                            }
                        }
                    )
                }
            }
        }
    }

    timePicker?.let { (initial, onConfirm) ->
        PickTimeDialog(
            initial = initial,
            onConfirm = { hhmm ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onConfirm(hhmm)
                timePicker = null
            },
            onDismiss = { timePicker = null }
        )
    }
}

/** A reminder's time: a row of its group, the time at its end, a tap to change it. */
@Composable
private fun TimeRow(label: String, time: String, onClick: () -> Unit) {
    SettingsRow(
        title = label,
        icon = Icons.Rounded.Schedule,
        onClick = onClick,
        trailing = { Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
    )
}
