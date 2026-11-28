@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodyException
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.components.ConnectedButtonRow
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateRangeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.TextPromptDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.connectedShapes
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsCheckRow
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsChoiceRow
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsGroup
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsNote
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPage
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPageScaffold
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPageSection
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsRow
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsSwitchRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The Modes hub's short pages (13.4, S2), each on the shared ModesViewModel. The modes live in the
 * vault: on a read-only device every page shows ReadOnlyNotice and its controls are off or gone
 * (settled decision #18); the ViewModel refuses the writes too.
 */

/** Names & icons: what the three modes and the tools hub are called. */
@Composable
fun ModesNamesScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appearance = config.appearance()
    val editable = !VaultWriteGate.readOnly
    var editMode by remember { mutableStateOf<ContextMode?>(null) }
    var editToolkit by remember { mutableStateOf(false) }

    SettingsPageScaffold(page = SettingsPage.MODES_NAMES, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsGroup {
            row { SettingsNote("What the modes and the tools hub are called on the Daily screen, the mode sheet and the Quick Settings tile.") }
            ContextMode.entries.forEach { mode ->
                row {
                    SettingsRow(
                        title = appearance.label(mode),
                        description = "${ModeAppearance.defaultLabel(mode)} mode",
                        icon = ModeIcons.of(appearance, mode),
                        iconKey = ModeAppearance.defaultLabel(mode),
                        onClick = { editMode = mode },
                        enabled = editable
                    )
                }
            }
            row {
                SettingsRow(
                    title = appearance.toolkitName,
                    description = "Tools hub: mission log, boards, meetings, objectives…",
                    icon = Icons.Rounded.Apps,
                    iconKey = ModeAppearance.DEFAULT_TOOLKIT,
                    onClick = { editToolkit = true },
                    enabled = editable
                )
            }
        }
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
}

/** What counts as work: the folders hidden outside the Work mode. */
@Composable
fun WorkFoldersScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadFolders() }
    val appearance = config.appearance()
    val editable = !VaultWriteGate.readOnly

    SettingsPageScaffold(page = SettingsPage.MODES_WORK, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsGroup {
            row {
                SettingsNote(
                    "Hidden outside ${appearance.label(ContextMode.WORK)} mode: lists, search, AI answers, resurfaced notes, " +
                        "widgets and notifications."
                )
            }
            if (folders.isEmpty()) row { SettingsNote("No folders yet — sync the vault first.") }
            folders.forEach { folder ->
                row {
                    SettingsCheckRow(
                        title = folder,
                        checked = folder in config.workFolders,
                        onCheckedChange = { viewModel.toggleWorkFolder(folder) },
                        enabled = editable
                    )
                }
            }
        }
    }
}

/** The Personal mode's projects: the ones it shows on the project screens (none ticked: all of them). */
@Composable
fun PersonalProjectsScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadProjects() }
    val appearance = config.appearance()
    val personalLabel = appearance.label(ContextMode.PERSONAL)
    val editable = !VaultWriteGate.readOnly
    // A project inside a work folder is a work one: the Personal mode never shows it.
    val personal = projects.filterNot { project -> config.workFolders.any { project.root == it || project.root.startsWith("$it/") } }

    SettingsPageScaffold(page = SettingsPage.MODES_PROJECTS, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsGroup {
            row {
                SettingsNote(
                    "What $personalLabel mode shows on the Projects tab, Daily, the widget and Due today; nothing ticked " +
                        "shows every personal project. ${appearance.label(ContextMode.OFF)} mode shows none."
                )
            }
            if (personal.isEmpty()) row { SettingsNote("No personal project yet — sync the vault first.") }
            personal.forEach { project ->
                row {
                    SettingsCheckRow(
                        title = project.name,
                        checked = project.root in config.personalProjects,
                        onCheckedChange = { viewModel.togglePersonalProject(project.root) },
                        enabled = editable
                    )
                }
            }
        }
    }
}

/** Schedule: the Work mode during work hours, another mode the rest of the time. */
@Composable
fun ScheduleScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appearance = config.appearance()
    val workLabel = appearance.label(ContextMode.WORK)
    val editable = !VaultWriteGate.readOnly
    var timePicker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    SettingsPageScaffold(page = SettingsPage.MODES_SCHEDULE, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Schedule",
                    checked = config.scheduleEnabled,
                    onCheckedChange = viewModel::setScheduleEnabled,
                    description = "$workLabel mode during work hours; another mode the rest of the time.",
                    icon = Icons.Rounded.Schedule,
                    enabled = editable
                )
            }
            if (config.scheduleEnabled) {
                row {
                    SettingsRow(
                        title = "Work days",
                        description = ModesSummary.days(config.workDays),
                        enabled = editable,
                        below = {
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
                        }
                    )
                }
                row {
                    SettingsRow(
                        title = "Starts",
                        onClick = { timePicker = config.workStart to { time -> viewModel.setWorkHours(time, config.workEnd) } },
                        enabled = editable,
                        trailing = { TimeText(config.workStart) }
                    )
                }
                row {
                    SettingsRow(
                        title = "Ends",
                        onClick = { timePicker = config.workEnd to { time -> viewModel.setWorkHours(config.workStart, time) } },
                        enabled = editable,
                        trailing = { TimeText(config.workEnd) }
                    )
                }
                // With the custody calendar on, the hours off follow who is home.
                if (config.custody.isActive) {
                    row { OutsideHoursChoice("With ${config.custody.label}", config.withChildMode, appearance, editable, viewModel::setWithChildMode) }
                    row { OutsideHoursChoice("Without ${config.custody.label}", config.outsideHoursMode, appearance, editable, viewModel::setOutsideHoursMode) }
                } else {
                    row { OutsideHoursChoice("Outside work hours", config.outsideHoursMode, appearance, editable, viewModel::setOutsideHoursMode) }
                }
            }
        }
    }

    timePicker?.let { (initial, onConfirm) ->
        PickTimeDialog(initial = initial, onConfirm = { onConfirm(it); timePicker = null }, onDismiss = { timePicker = null })
    }
}

/** Shared custody: every other week, from one handover to the next, and the days that leave the rhythm. */
@Composable
fun CustodyScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appearance = config.appearance()
    val custody = config.custody
    val editable = !VaultWriteGate.readOnly
    var editChildName by remember { mutableStateOf(false) }
    var pickException by remember { mutableStateOf(false) }
    var pendingException by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }

    SettingsPageScaffold(page = SettingsPage.MODES_CUSTODY, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Shared custody",
                    checked = custody.enabled,
                    onCheckedChange = viewModel::setCustodyEnabled,
                    description = "Every other week, from one handover to the next. Outside work hours and on leave the mode " +
                        "follows who is home, and habits can follow it too.",
                    icon = Icons.Rounded.FamilyRestroom,
                    enabled = editable
                )
            }
            if (custody.enabled) {
                row {
                    SettingsRow(
                        title = custody.name.ifBlank { "Name" },
                        description = "Shown as \"With ${custody.label}\"",
                        icon = Icons.Rounded.Badge,
                        iconKey = "custody_name",
                        onClick = { editChildName = true },
                        enabled = editable
                    )
                }
                row {
                    SettingsRow(
                        title = "Handover day",
                        description = custody.handoverDayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                        enabled = editable,
                        below = {
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
                        }
                    )
                }
                row {
                    val dayName = custody.handoverDayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    val arrives = custody.arrivesInWeekOf(LocalDate.now())
                    SettingsRow(
                        title = "This week, ${custody.label}…",
                        description = if (arrives == null) "Say how this week goes to start the rhythm." else null,
                        icon = Icons.Rounded.Today,
                        enabled = editable,
                        below = {
                            ConnectedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                ToggleButton(
                                    checked = arrives == true,
                                    onCheckedChange = { viewModel.setCustodyThisWeek(arrives = true) },
                                    enabled = editable,
                                    shapes = connectedShapes(0, 2),
                                    modifier = Modifier.weight(1f)
                                ) { Text("arrives on $dayName") }
                                ToggleButton(
                                    checked = arrives == false,
                                    onCheckedChange = { viewModel.setCustodyThisWeek(arrives = false) },
                                    enabled = editable,
                                    shapes = connectedShapes(1, 2),
                                    modifier = Modifier.weight(1f)
                                ) { Text("leaves on $dayName") }
                            }
                        }
                    )
                }
            }
        }
        if (custody.enabled) {
            SettingsPageSection("Exceptions") {
                if (custody.exceptions.isEmpty()) row { SettingsNote("School holidays or a swapped weekend: add them here.") }
                custody.exceptions.forEach { exception ->
                    val who = (if (exception.withChild) "With " else "Without ") + custody.label
                    row {
                        SettingsRow(
                            title = exception.label.ifBlank { who },
                            description = "${exception.start} → ${exception.end}" + if (exception.label.isNotBlank()) " · $who" else "",
                            trailing = if (editable) {
                                { RemoveButton { viewModel.removeCustodyException(exception) } }
                            } else {
                                null
                            }
                        )
                    }
                }
                if (editable) {
                    row { SettingsRow(title = "Add exception", icon = Icons.Rounded.Add, onClick = { pickException = true }) }
                }
            }
        }
    }

    if (editChildName) {
        TextPromptDialog(
            title = "Your child's name",
            label = "Name",
            initial = custody.name,
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
            childLabel = custody.label,
            onConfirm = { withChild, label ->
                viewModel.addCustodyException(CustodyException(start.toString(), end.toString(), withChild, label.trim()))
                pendingException = null
            },
            onDismiss = { pendingException = null }
        )
    }
}

/** Leave & key dates: the periods with no Work mode, and the countdowns on the Daily screen. */
@Composable
fun LeaveDatesScreen(onBack: () -> Unit, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appearance = config.appearance()
    val workLabel = appearance.label(ContextMode.WORK)
    val editable = !VaultWriteGate.readOnly
    var pickLeave by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pickKeyDate by remember { mutableStateOf(false) }
    var pendingKeyDate by remember { mutableStateOf<LocalDate?>(null) }
    var pendingKeyDateWork by remember { mutableStateOf(true) }

    SettingsPageScaffold(page = SettingsPage.MODES_DATES, onBack = onBack, appearance = appearance) {
        if (!editable) ReadOnlyNotice()
        SettingsPageSection("Leave") {
            row {
                SettingsNote(
                    if (config.custody.isActive) {
                        "No $workLabel for the whole period (${appearance.label(config.withChildMode)} with ${config.custody.label}, " +
                            "${appearance.label(config.outsideHoursMode)} without); a summary of what happened waits for you on the first day back."
                    } else {
                        "${appearance.label(ContextMode.OFF)} for the whole period; a summary of what happened waits for you on the first day back."
                    }
                )
            }
            if (config.leavePeriods.isEmpty()) row { SettingsNote("No leave planned.") }
            config.leavePeriods.forEach { leave ->
                row {
                    SettingsRow(
                        title = leave.label.ifBlank { "Leave" },
                        // A leave the calendar made follows its event (E3): change it in the calendar.
                        description = "${leave.start} → ${leave.end}" + if (leave.fromCalendar) " · from your calendar" else "",
                        icon = if (leave.fromCalendar) Icons.Rounded.CalendarMonth else null,
                        trailing = if (editable && !leave.fromCalendar) {
                            { RemoveButton { viewModel.removeLeave(leave) } }
                        } else {
                            null
                        }
                    )
                }
            }
            if (editable) row { SettingsRow(title = "Add leave", icon = Icons.Rounded.Add, onClick = { pickLeave = true }) }
        }
        SettingsPageSection("Key dates") {
            row { SettingsNote("Countdowns on the Daily screen. Work dates hide outside $workLabel mode.") }
            if (config.keyDates.isEmpty()) row { SettingsNote("No key dates.") }
            config.keyDates.forEach { key ->
                row {
                    SettingsRow(
                        title = key.label,
                        description = key.date + if (key.work) " · work" else "",
                        trailing = if (editable) {
                            { RemoveButton { viewModel.removeKeyDate(key) } }
                        } else {
                            null
                        }
                    )
                }
            }
            if (editable) {
                row {
                    SettingsSwitchRow(
                        title = "Next date is a work date",
                        checked = pendingKeyDateWork,
                        onCheckedChange = { pendingKeyDateWork = it },
                        description = "A work date shows only in $workLabel mode"
                    )
                }
                row { SettingsRow(title = "Add key date", icon = Icons.Rounded.Add, onClick = { pickKeyDate = true }) }
            }
        }
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

/** The mode for some hours off, Personal or Off, as a connected pair named after the owner's modes. */
@Composable
private fun OutsideHoursChoice(
    title: String,
    selected: ContextMode,
    appearance: ModeAppearance,
    editable: Boolean,
    onSelect: (ContextMode) -> Unit
) {
    val choices = listOf(ContextMode.PERSONAL, ContextMode.OFF)
    SettingsChoiceRow(
        title = title,
        choices = choices.map { appearance.label(it) },
        selected = choices.indexOf(selected),
        onSelect = { onSelect(choices[it]) },
        enabled = editable
    )
}

/** A time a row holds, at its end. */
@Composable
private fun TimeText(time: String) {
    Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** A row's Remove, at its end. */
@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    TextButton(onClick = onRemove, shapes = ButtonDefaults.shapes()) { Text("Remove") }
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
                TextButton(onClick = { onConfirm(false, label) }, shapes = ButtonDefaults.shapes()) { Text("Without $childLabel") }
                TextButton(onClick = { onConfirm(true, label) }, shapes = ButtonDefaults.shapes()) { Text("With $childLabel") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}
