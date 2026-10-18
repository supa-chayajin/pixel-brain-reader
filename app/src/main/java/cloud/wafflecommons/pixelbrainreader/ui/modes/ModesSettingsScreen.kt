package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateRangeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.TextPromptDialog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModesSettingsScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadFolders() }

    var timePicker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var pickLeave by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pickKeyDate by remember { mutableStateOf(false) }
    var pendingKeyDate by remember { mutableStateOf<LocalDate?>(null) }
    var pendingKeyDateWork by remember { mutableStateOf(true) }

    FormScaffold(title = "Modes", onBack = onBack) {
        SectionCard(
            title = "Work folders",
            icon = Icons.Rounded.FolderShared,
            subtitle = "Hidden outside Work mode: lists, search, AI answers, resurfaced notes, widgets and notifications."
        ) {
            if (folders.isEmpty()) EmptyHint("No folders yet — sync the vault first.")
            folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = folder in config.workFolders, onCheckedChange = { viewModel.toggleWorkFolder(folder) })
                    Text(folder, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionCard(
            title = "Schedule",
            icon = Icons.Rounded.Schedule,
            subtitle = "Work mode during work hours; another mode the rest of the time.",
            trailing = { Switch(checked = config.scheduleEnabled, onCheckedChange = viewModel::setScheduleEnabled) }
        ) {
            if (config.scheduleEnabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day.value in config.workDays,
                            onClick = { viewModel.toggleWorkDay(day.value) },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timePicker = config.workStart to { t -> viewModel.setWorkHours(t, config.workEnd) } }) {
                        Text("From ${config.workStart}")
                    }
                    TextButton(onClick = { timePicker = config.workEnd to { t -> viewModel.setWorkHours(config.workStart, t) } }) {
                        Text("to ${config.workEnd}")
                    }
                }
                Text("Outside work hours", style = MaterialTheme.typography.labelLarge)
                val choices = listOf(ContextMode.PERSONAL, ContextMode.OFF)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = config.outsideHoursMode == mode,
                            onClick = { viewModel.setOutsideHoursMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size)
                        ) { Text(mode.label()) }
                    }
                }
            }
        }

        SectionCard(
            title = "Leave",
            icon = Icons.Rounded.BeachAccess,
            subtitle = "Off for the whole period; a summary of what happened waits for you on the first day back."
        ) {
            if (config.leavePeriods.isEmpty()) EmptyHint("No leave planned.")
            config.leavePeriods.forEach { leave ->
                RemovableRow(
                    title = leave.label.ifBlank { "Leave" },
                    subtitle = "${leave.start} → ${leave.end}",
                    onRemove = { viewModel.removeLeave(leave) }
                )
            }
            FilledTonalButton(onClick = { pickLeave = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add leave")
            }
        }

        SectionCard(
            title = "Key dates",
            icon = Icons.Rounded.Event,
            subtitle = "Countdowns on the Daily screen. Work dates hide outside Work mode."
        ) {
            if (config.keyDates.isEmpty()) EmptyHint("No key dates.")
            config.keyDates.forEach { key ->
                RemovableRow(
                    title = key.label,
                    subtitle = key.date + if (key.work) " · work" else "",
                    onRemove = { viewModel.removeKeyDate(key) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pendingKeyDateWork, onCheckedChange = { pendingKeyDateWork = it })
                Spacer(Modifier.width(8.dp))
                Text("Next date is a work date", style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(onClick = { pickKeyDate = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add key date")
            }
        }

        SectionCard(
            title = "Screen-share safe",
            icon = Icons.Rounded.VisibilityOff,
            subtitle = "Hides work, private and health content everywhere until you turn it off.",
            trailing = { Switch(checked = visibility.screenShareSafe, onCheckedChange = viewModel::setScreenShareSafe) }
        )
    }

    timePicker?.let { (initial, onConfirm) ->
        PickTimeDialog(initial = initial, onConfirm = { onConfirm(it); timePicker = null }, onDismiss = { timePicker = null })
    }
    if (pickLeave) {
        PickDateRangeDialog(
            onConfirm = { start, end -> pendingLeave = start to end; pickLeave = false },
            onDismiss = { pickLeave = false }
        )
    }
    pendingLeave?.let { (start, end) ->
        TextPromptDialog(
            title = "Name this leave",
            label = "Label",
            initial = "Leave",
            onConfirm = { label ->
                viewModel.addLeave(LeavePeriod(start.toString(), end.toString(), label))
                pendingLeave = null
            },
            onDismiss = { pendingLeave = null }
        )
    }
    if (pickKeyDate) {
        PickDateDialog(
            initial = LocalDate.now(),
            onConfirm = { date -> pendingKeyDate = date; pickKeyDate = false },
            onDismiss = { pickKeyDate = false }
        )
    }
    pendingKeyDate?.let { date ->
        TextPromptDialog(
            title = "What happens on $date?",
            label = "Label",
            onConfirm = { label ->
                viewModel.addKeyDate(KeyDate(date.toString(), label, pendingKeyDateWork))
                pendingKeyDate = null
            },
            onDismiss = { pendingKeyDate = null }
        )
    }
}

