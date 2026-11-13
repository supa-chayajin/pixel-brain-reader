package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodyException
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateRangeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.RemovableRow
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
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.loadFolders()
        viewModel.loadProjects()
    }

    var timePicker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var pickLeave by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pickKeyDate by remember { mutableStateOf(false) }
    var pendingKeyDate by remember { mutableStateOf<LocalDate?>(null) }
    var pendingKeyDateWork by remember { mutableStateOf(true) }
    var editMode by remember { mutableStateOf<ContextMode?>(null) }
    var editToolkit by remember { mutableStateOf(false) }
    var editChildName by remember { mutableStateOf(false) }
    var pickException by remember { mutableStateOf(false) }
    var pendingException by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    val appearance = config.appearance()
    val workLabel = appearance.label(ContextMode.WORK)
    // The modes live in the vault: a read-only device shows them and changes only screen-share safe,
    // a setting of its own (12.15, S2).
    val editable = !VaultWriteGate.readOnly

    FormScaffold(title = "Modes", onBack = onBack) {
        if (!editable) ReadOnlyNotice()
        SectionCard(
            title = "Names & icons",
            icon = Icons.Rounded.Edit,
            subtitle = "What the modes and the tools hub are called on the Daily screen, the mode sheet and the Quick Settings tile."
        ) {
            ContextMode.entries.forEach { mode ->
                ListItem(
                    supportingContent = { Text("${ModeAppearance.defaultLabel(mode)} mode") },
                    leadingContent = { Icon(ModeIcons.of(appearance, mode), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = editable) { editMode = mode }
                ) { Text(appearance.label(mode)) }
            }
            ListItem(
                supportingContent = { Text("Tools hub: mission log, boards, meetings, objectives…") },
                leadingContent = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = editable) { editToolkit = true }
            ) { Text(appearance.toolkitName) }
        }

        SectionCard(
            title = "Work folders",
            icon = Icons.Rounded.FolderShared,
            subtitle = "Hidden outside $workLabel mode: lists, search, AI answers, resurfaced notes, widgets and notifications."
        ) {
            if (folders.isEmpty()) EmptyHint("No folders yet — sync the vault first.")
            folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = folder in config.workFolders, onCheckedChange = { viewModel.toggleWorkFolder(folder) }, enabled = editable)
                    Text(folder, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        val personalLabel = appearance.label(ContextMode.PERSONAL)
        SectionCard(
            title = "$personalLabel projects",
            icon = Icons.Rounded.RocketLaunch,
            subtitle = "What $personalLabel mode shows on the Projects tab, Daily, the widget and Due today; nothing ticked shows every personal project. " +
                "${appearance.label(ContextMode.OFF)} mode shows none."
        ) {
            val personal = projects.filterNot { p -> config.workFolders.any { p.root == it || p.root.startsWith("$it/") } }
            if (personal.isEmpty()) EmptyHint("No personal project yet — sync the vault first.")
            personal.forEach { project ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = project.root in config.personalProjects,
                        onCheckedChange = { viewModel.togglePersonalProject(project.root) },
                        enabled = editable
                    )
                    Text(project.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionCard(
            title = "Schedule",
            icon = Icons.Rounded.Schedule,
            subtitle = "$workLabel mode during work hours; another mode the rest of the time.",
            trailing = { Switch(checked = config.scheduleEnabled, onCheckedChange = viewModel::setScheduleEnabled, enabled = editable) }
        ) {
            if (config.scheduleEnabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day.value in config.workDays,
                            onClick = { viewModel.toggleWorkDay(day.value) },
                            enabled = editable,
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = editable, onClick = { timePicker = config.workStart to { t -> viewModel.setWorkHours(t, config.workEnd) } }) {
                        Text("From ${config.workStart}")
                    }
                    TextButton(enabled = editable, onClick = { timePicker = config.workEnd to { t -> viewModel.setWorkHours(config.workStart, t) } }) {
                        Text("to ${config.workEnd}")
                    }
                }
                // With the custody calendar on, the hours off follow who is home.
                if (config.custody.isActive) {
                    OutsideHoursChoice("With ${config.custody.label}", config.withChildMode, appearance, editable, viewModel::setWithChildMode)
                    OutsideHoursChoice("Without ${config.custody.label}", config.outsideHoursMode, appearance, editable, viewModel::setOutsideHoursMode)
                } else {
                    OutsideHoursChoice("Outside work hours", config.outsideHoursMode, appearance, editable, viewModel::setOutsideHoursMode)
                }
            }
        }

        val custody = config.custody
        SectionCard(
            title = "Shared custody",
            icon = Icons.Rounded.FamilyRestroom,
            subtitle = "Every other week, from one handover to the next. Outside work hours and on leave the mode follows who is home, and habits can follow it too.",
            trailing = { Switch(checked = custody.enabled, onCheckedChange = viewModel::setCustodyEnabled, enabled = editable) }
        ) {
            if (custody.enabled) {
                ListItem(
                    supportingContent = { Text("Shown as \"With ${custody.label}\"") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = editable) { editChildName = true }
                ) { Text(custody.name.ifBlank { "Name" }) }
                Text("Handover day", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day == custody.handoverDayOfWeek,
                            onClick = { viewModel.setHandoverDay(day) },
                            enabled = editable,
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) }
                        )
                    }
                }
                val dayName = custody.handoverDayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                val arrives = custody.arrivesInWeekOf(LocalDate.now())
                Text("This week, ${custody.label}…", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = arrives == true,
                        onClick = { viewModel.setCustodyThisWeek(arrives = true) },
                        enabled = editable,
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("arrives on $dayName") }
                    SegmentedButton(
                        selected = arrives == false,
                        onClick = { viewModel.setCustodyThisWeek(arrives = false) },
                        enabled = editable,
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("leaves on $dayName") }
                }
                if (arrives == null) EmptyHint("Say how this week goes to start the rhythm.")
                Text("Exceptions", style = MaterialTheme.typography.labelLarge)
                if (custody.exceptions.isEmpty()) EmptyHint("School holidays or a swapped weekend: add them here.")
                custody.exceptions.forEach { exception ->
                    val who = (if (exception.withChild) "With " else "Without ") + custody.label
                    RemovableRow(
                        title = exception.label.ifBlank { who },
                        subtitle = "${exception.start} → ${exception.end}" + if (exception.label.isNotBlank()) " · $who" else "",
                        onRemove = if (editable) {
                            { viewModel.removeCustodyException(exception) }
                        } else {
                            null
                        }
                    )
                }
                if (editable) FilledTonalButton(onClick = { pickException = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add exception")
                }
            }
        }

        SectionCard(
            title = "Leave",
            icon = Icons.Rounded.BeachAccess,
            subtitle = if (config.custody.isActive) {
                "No $workLabel for the whole period (${appearance.label(config.withChildMode)} with ${config.custody.label}, " +
                    "${appearance.label(config.outsideHoursMode)} without); a summary of what happened waits for you on the first day back."
            } else {
                "Off for the whole period; a summary of what happened waits for you on the first day back."
            }
        ) {
            if (config.leavePeriods.isEmpty()) EmptyHint("No leave planned.")
            config.leavePeriods.forEach { leave ->
                RemovableRow(
                    title = leave.label.ifBlank { "Leave" },
                    subtitle = "${leave.start} → ${leave.end}",
                    onRemove = if (editable) {
                        { viewModel.removeLeave(leave) }
                    } else {
                        null
                    }
                )
            }
            if (editable) FilledTonalButton(onClick = { pickLeave = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add leave")
            }
        }

        SectionCard(
            title = "Key dates",
            icon = Icons.Rounded.Event,
            subtitle = "Countdowns on the Daily screen. Work dates hide outside $workLabel mode."
        ) {
            if (config.keyDates.isEmpty()) EmptyHint("No key dates.")
            config.keyDates.forEach { key ->
                RemovableRow(
                    title = key.label,
                    subtitle = key.date + if (key.work) " · work" else "",
                    onRemove = if (editable) {
                        { viewModel.removeKeyDate(key) }
                    } else {
                        null
                    }
                )
            }
            if (editable) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pendingKeyDateWork, onCheckedChange = { pendingKeyDateWork = it })
                Spacer(Modifier.width(8.dp))
                Text("Next date is a work date", style = MaterialTheme.typography.bodyMedium)
            }
            if (editable) FilledTonalButton(onClick = { pickKeyDate = true }) {
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

    editMode?.let { mode ->
        ModeStyleDialog(
            mode = mode,
            appearance = appearance,
            onConfirm = { style -> viewModel.setModeStyle(mode, style); editMode = null },
            onDismiss = { editMode = null }
        )
    }
    if (editToolkit) {
        TextPromptDialog(
            title = "Name the tools hub",
            label = "Name",
            initial = appearance.toolkitName,
            onConfirm = { name -> viewModel.setToolkitName(name); editToolkit = false },
            onDismiss = { editToolkit = false }
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
    if (editChildName) {
        TextPromptDialog(
            title = "Your child's name",
            label = "Name",
            initial = config.custody.name,
            onConfirm = { name -> viewModel.setChildName(name); editChildName = false },
            onDismiss = { editChildName = false }
        )
    }
    if (pickException) {
        PickDateRangeDialog(
            onConfirm = { start, end -> pendingException = start to end; pickException = false },
            onDismiss = { pickException = false }
        )
    }
    pendingException?.let { (start, end) ->
        CustodyExceptionDialog(
            childLabel = config.custody.label,
            onConfirm = { withChild, label ->
                viewModel.addCustodyException(CustodyException(start.toString(), end.toString(), withChild, label.trim()))
                pendingException = null
            },
            onDismiss = { pendingException = null }
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

/** The mode for some hours off: Perso or Off. */
@Composable
private fun OutsideHoursChoice(
    title: String,
    selected: ContextMode,
    appearance: ModeAppearance,
    editable: Boolean,
    onSelect: (ContextMode) -> Unit
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    val choices = listOf(ContextMode.PERSONAL, ContextMode.OFF)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                enabled = editable,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size)
            ) { Text(appearance.label(mode)) }
        }
    }
}

/** An exception's side (with or without the child) and an optional label. */
@Composable
private fun CustodyExceptionDialog(childLabel: String, onConfirm: (Boolean, String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is home those days?") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(ModeAppearance.MAX_LABEL * 2) },
                label = { Text("Label (optional)") },
                singleLine = true
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onConfirm(false, label) }) { Text("Without $childLabel") }
                TextButton(onClick = { onConfirm(true, label) }) { Text("With $childLabel") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
