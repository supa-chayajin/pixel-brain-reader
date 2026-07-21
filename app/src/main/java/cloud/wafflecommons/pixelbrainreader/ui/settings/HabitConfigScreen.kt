package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: HabitConfigViewModel = hiltViewModel()
) {
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val activeHabits = habits.filter { !it.archived }.sortedBy { it.sortOrder }
    val haptic = LocalHapticFeedback.current

    var editingHabit by remember { mutableStateOf<HabitConfig?>(null) }
    var isSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gérer les habitudes et automatisations") },
                navigationIcon = {
                    CortexIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        floatingActionButton = {
            // Lift the FAB above the floating ExpressiveNavBar (which overlays content).
            Box(modifier = Modifier.padding(bottom = 66.dp)) {
                FloatingActionButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    editingHabit = HabitConfig(
                        id = UUID.randomUUID().toString(),
                        title = "",
                        createdDate = LocalDate.now().toString()
                    )
                    isSheetOpen = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Ajouter une habitude")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeHabits, key = { it.id }) { habit ->
                ElevatedCard(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        editingHabit = habit
                        isSheetOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().animateItem()
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(habit.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!habit.autoSource.isNullOrBlank()) {
                                AssistChip(
                                    onClick = { },
                                    label = { Text("🤖 Auto") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                        labelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                        if (habit.description.isNotBlank()) {
                            Text(habit.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                        }
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler.describe(habit),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (isSheetOpen && editingHabit != null) {
        ModalBottomSheet(
            onDismissRequest = { isSheetOpen = false },
            sheetState = sheetState
        ) {
            HabitEditorForm(
                initialHabit = editingHabit!!,
                onSave = { updated ->
                    viewModel.saveHabit(updated)
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                },
                onDelete = {
                    viewModel.deleteHabit(editingHabit!!)
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                },
                onCancel = {
                    coroutineScope.launch { sheetState.hide(); isSheetOpen = false }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorForm(
    initialHabit: HabitConfig,
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
    var autoSource by remember { mutableStateOf(initialHabit.autoSource ?: "None (Manual)") }
    val haptic = LocalHapticFeedback.current

    val autoSources = listOf(
        "None (Manual)",
        "health_connect_steps",
        "health_connect_sleep",
        "health_connect_hydration",
        "health_connect_nutrition",
        "health_connect_active_minutes",
        "health_connect_distance",
        "health_connect_mindfulness",
        "health_connect_weight"
    )

    var expandedAutoSource by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Modifier l'habitude", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Titre") },
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
            Text("Type : ", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = type == HabitType.BOOLEAN,
                    onClick = { type = HabitType.BOOLEAN },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("À cocher")
                }
                SegmentedButton(
                    selected = type == HabitType.MEASURABLE,
                    onClick = { type = HabitType.MEASURABLE },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Objectif")
                }
            }
        }

        AnimatedVisibility(visible = type == HabitType.MEASURABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = targetValue,
                    onValueChange = { targetValue = it },
                    label = { Text("Objectif") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Unité (ex. h, L)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }

        Text("Planification", style = MaterialTheme.typography.titleMedium)
        val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf("WEEKLY" to "Hebdo", "BIWEEKLY" to "2 sem", "INTERVAL" to "Intervalle")
            modes.forEachIndexed { index, (mode, lbl) ->
                SegmentedButton(
                    selected = scheduleMode == mode,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scheduleMode = mode
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                ) { Text(lbl) }
            }
        }

        when (scheduleMode) {
            "INTERVAL" -> {
                Text(
                    "Se répète tous les N jours / semaines / mois depuis la dernière réalisation, quel que soit le jour de la semaine.",
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
                        label = { Text("Tous les") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                        singleLine = true
                    )
                    SingleChoiceSegmentedButtonRow {
                        val units = listOf("DAY" to "jours", "WEEK" to "sem", "MONTH" to "mois")
                        units.forEachIndexed { index, (u, lbl) ->
                            SegmentedButton(
                                selected = intervalUnit == u,
                                onClick = { intervalUnit = u },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = units.size)
                            ) { Text(lbl) }
                        }
                    }
                }
            }
            "BIWEEKLY" -> {
                listOf(1, 2).forEach { week ->
                    Text("Semaine $week", style = MaterialTheme.typography.bodyMedium)
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

        ExposedDropdownMenuBox(
            expanded = expandedAutoSource,
            onExpandedChange = { expandedAutoSource = it }
        ) {
            OutlinedTextField(
                value = autoSource,
                onValueChange = {},
                readOnly = true,
                label = { Text("Source auto (Health Connect)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAutoSource) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expandedAutoSource,
                onDismissRequest = { expandedAutoSource = false }
            ) {
                autoSources.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source) },
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
            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                Spacer(Modifier.width(8.dp))
                Text("Supprimer")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCancel()
                }) { Text("Annuler") }
                Button(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val finalSource = if (autoSource == "None (Manual)") null else autoSource
                    val target = targetValue.toDoubleOrNull() ?: 0.0
                    val updated = initialHabit.copy(
                        title = title.takeIf { it.isNotBlank() } ?: "Habitude sans nom",
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
                        autoSource = finalSource
                    )
                    onSave(updated)
                }) {
                    Text("Enregistrer")
                }
            }
        }
    }
}
