@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodySchedule
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import cloud.wafflecommons.pixelbrainreader.ui.components.ConnectedButtonRow
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.connectedShapes
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.theme.LocalNavRail
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/**
 * Settings › Life OS › Habits: the habits as a segmented list, each opening its editor; + adds one.
 * 13.4 (S-k): the Settings frame, "New habit" for a new one, Archive (it keeps the logs) only on an
 * existing one, the custody Days as a connected group and the automatic sources by name. A
 * read-only device lists the habits and edits none (12.15, S2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: HabitConfigViewModel = hiltViewModel()
) {
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val custody by viewModel.custody.collectAsStateWithLifecycle()
    val activeHabits = habits.filter { !it.archived }.sortedBy { it.sortOrder }
    val haptic = LocalHapticFeedback.current
    val readOnly = VaultWriteGate.readOnly

    var editingHabit by remember { mutableStateOf<HabitConfig?>(null) }
    var isSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val coroutineScope = rememberCoroutineScope()

    SettingsLazyPageScaffold(
        page = SettingsPage.HABITS,
        onBack = onNavigateBack,
        floatingActionButton = {
            // Lift the FAB above the floating ExpressiveNavBar (which overlays content); none beside the rail.
            if (!readOnly) Box(modifier = Modifier.padding(bottom = if (LocalNavRail.current) 0.dp else 66.dp)) {
                FloatingActionButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    editingHabit = HabitConfig(
                        id = UUID.randomUUID().toString(),
                        title = "",
                        createdDate = LocalDate.now().toString()
                    )
                    isSheetOpen = true
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add a habit")
                }
            }
        }
    ) {
        if (readOnly) item(key = "read-only") { ReadOnlyNotice(Modifier.padding(bottom = 14.dp)) }
        if (activeHabits.isEmpty()) {
            item(key = "empty") { SettingsNote(if (readOnly) "No habits yet." else "No habits yet: add one with +.") }
        }
        itemsIndexed(activeHabits, key = { _, habit -> habit.id }) { index, habit ->
            val automatic = !habit.autoSource.isNullOrBlank()
            SettingsRow(
                title = habit.title,
                description = habitLine(habit, custody),
                icon = if (automatic) Icons.Rounded.AutoMode else Icons.Rounded.TaskAlt,
                iconKey = habit.title,
                shape = segmentShape(index, activeHabits.size),
                onClick = if (readOnly) {
                    null
                } else {
                    {
                        editingHabit = habit
                        isSheetOpen = true
                    }
                },
                modifier = Modifier.animateItem()
            )
        }
    }

    val editing = editingHabit
    if (isSheetOpen && editing != null) {
        ModalBottomSheet(
            onDismissRequest = { isSheetOpen = false },
            sheetState = sheetState
        ) {
            HabitEditorForm(
                initialHabit = editing,
                custody = custody,
                isNew = habits.none { it.id == editing.id },
                onSave = { updated ->
                    viewModel.saveHabit(updated)
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                },
                onDelete = {
                    viewModel.deleteHabit(editing)
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                },
                onCancel = {
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                }
            )
        }
    }
}

/** A habit's line in the list: its own description, then its schedule and, for an automatic one, its source. */
private fun habitLine(habit: HabitConfig, custody: CustodySchedule): String {
    val schedule = HabitScheduler.describe(habit, custody.label.takeIf { custody.isActive })
    val source = habit.autoSource?.takeIf { it.isNotBlank() }?.let { " · " + HabitSources.label(it) }.orEmpty()
    return listOfNotNull(habit.description.trim().takeIf { it.isNotEmpty() }, schedule + source).joinToString("\n")
}

/**
 * A habit's editor, in a bottom sheet. [isNew]: a habit not saved yet, titled "New habit" and with
 * no Archive (13.4, S-k). [onDelete] archives the habit: its logs stay.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorForm(
    initialHabit: HabitConfig,
    custody: CustodySchedule,
    isNew: Boolean,
    onSave: (HabitConfig) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf(initialHabit.title) }
    var description by remember { mutableStateOf(initialHabit.description) }
    var type by remember { mutableStateOf(initialHabit.type) }
    var targetValue by remember { mutableStateOf(if (initialHabit.targetValue > 0) initialHabit.targetValue.toString() else "") }
    var unit by remember { mutableStateOf(initialHabit.unit) }
    // For WEEKLY this set holds weekday keys ("MON"…); for BIWEEKLY it holds 2-week slots
    // ("W1-MON", "W2-FRI"). Interpreted per the selected scheduleMode.
    var frequency by remember { mutableStateOf(initialHabit.frequency.toSet()) }
    var scheduleMode by remember { mutableStateOf(initialHabit.scheduleMode.uppercase().ifBlank { "WEEKLY" }) }
    var intervalCount by remember { mutableStateOf(if (initialHabit.intervalCount > 0) initialHabit.intervalCount.toString() else "2") }
    var intervalUnit by remember { mutableStateOf(initialHabit.intervalUnit.uppercase().ifBlank { "DAY" }) }
    // "" any day, or HabitScheduler.WITH_CHILD / WITHOUT_CHILD (the Modes custody calendar).
    var custodyChoice by remember { mutableStateOf(initialHabit.custody.trim().uppercase()) }
    // A HabitSources key; null: ticked by hand. A key this version doesn't know is kept as it is.
    var autoSource by remember { mutableStateOf(initialHabit.autoSource?.takeIf { it.isNotBlank() }) }
    val haptic = LocalHapticFeedback.current

    var expandedAutoSource by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(if (isNew) "New habit" else "Edit habit", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Type", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(16.dp))
            ConnectedButtonRow {
                ToggleButton(
                    checked = type == HabitType.BOOLEAN,
                    onCheckedChange = { type = HabitType.BOOLEAN },
                    shapes = connectedShapes(0, 2),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Checklist")
                }
                ToggleButton(
                    checked = type == HabitType.MEASURABLE,
                    onCheckedChange = { type = HabitType.MEASURABLE },
                    shapes = connectedShapes(1, 2),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Target")
                }
            }
        }

        AnimatedVisibility(visible = type == HabitType.MEASURABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = targetValue,
                    onValueChange = { targetValue = it },
                    label = { Text("Target") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Unit (e.g. h, L)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }

        Text("Schedule", style = MaterialTheme.typography.titleMedium)
        val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        ConnectedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf("WEEKLY" to "Weekly", "BIWEEKLY" to "2 wk", "INTERVAL" to "Interval")
            modes.forEachIndexed { index, (mode, lbl) ->
                ToggleButton(
                    checked = scheduleMode == mode,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scheduleMode = mode
                    },
                    shapes = connectedShapes(index, modes.size),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(lbl)
                }
            }
        }

        when (scheduleMode) {
            "INTERVAL" -> {
                Text(
                    "Repeats every N days / weeks / months since the last completion, regardless of the day of week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = intervalCount,
                        onValueChange = { intervalCount = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Every") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                        singleLine = true
                    )
                    ConnectedButtonRow {
                        val units = listOf("DAY" to "days", "WEEK" to "wk", "MONTH" to "months")
                        units.forEachIndexed { index, (u, lbl) ->
                            ToggleButton(
                                checked = intervalUnit == u,
                                onCheckedChange = { intervalUnit = u },
                                shapes = connectedShapes(index, units.size),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(lbl)
                            }
                        }
                    }
                }
            }
            "BIWEEKLY" -> {
                val thisWeek = HabitScheduler.biweeklyWeekOf(LocalDate.now())
                listOf(1, 2).forEach { week ->
                    Text(if (week == thisWeek) "Week $week · this week" else "Week $week", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        days.forEach { day ->
                            val slot = "W$week-$day"
                            FilterChip(
                                selected = frequency.contains(slot),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    frequency = if (frequency.contains(slot)) frequency - slot else frequency + slot
                                },
                                label = { Text(day) }
                            )
                        }
                    }
                }
            }
            else -> { // WEEKLY
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    days.forEach { day ->
                        FilterChip(
                            selected = frequency.contains(day),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                frequency = if (frequency.contains(day)) frequency - day else frequency + day
                            },
                            label = { Text(day) }
                        )
                    }
                }
            }
        }

        // The custody calendar's days, on top of the schedule (only while the calendar is on): a connected group.
        if (custody.isActive) {
            Text("Days", style = MaterialTheme.typography.titleMedium)
            val choices = listOf(
                "" to "Any day",
                HabitScheduler.WITH_CHILD to "With ${custody.label}",
                HabitScheduler.WITHOUT_CHILD to "Without ${custody.label}"
            )
            ConnectedButtonRow(modifier = Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, (choice, lbl) ->
                    ToggleButton(
                        checked = custodyChoice == choice,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            custodyChoice = choice
                        },
                        shapes = connectedShapes(index, choices.size),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(lbl, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = expandedAutoSource,
            onExpandedChange = { expandedAutoSource = it }
        ) {
            OutlinedTextField(
                value = HabitSources.label(autoSource),
                onValueChange = {},
                readOnly = true,
                label = { Text("Automatic source") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAutoSource) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expandedAutoSource,
                onDismissRequest = { expandedAutoSource = false }
            ) {
                HabitSources.KEYS.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(HabitSources.label(source)) },
                        onClick = {
                            autoSource = source
                            expandedAutoSource = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Archive keeps the habit's logs; a habit not saved yet has nothing to archive.
            if (!isNew) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Rounded.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Archive")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCancel()
                }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val target = targetValue.toDoubleOrNull() ?: 0.0
                    val updated = initialHabit.copy(
                        title = title.takeIf { it.isNotBlank() } ?: "Unnamed habit",
                        description = description,
                        type = type,
                        targetValue = if (type == HabitType.MEASURABLE) target else 0.0,
                        unit = unit,
                        // Persist only the slots that belong to the selected mode.
                        frequency = when (scheduleMode) {
                            "BIWEEKLY" -> frequency.filter { it.startsWith("W1-") || it.startsWith("W2-") }.toList()
                            "INTERVAL" -> emptyList()
                            else -> frequency.filter { it in days }.toList()
                        },
                        scheduleMode = scheduleMode,
                        intervalCount = intervalCount.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        intervalUnit = intervalUnit,
                        autoSource = autoSource,
                        custody = custodyChoice
                    )
                    onSave(updated)
                }, shapes = ButtonDefaults.shapes()) {
                    Text("Save")
                }
            }
        }
    }
}
