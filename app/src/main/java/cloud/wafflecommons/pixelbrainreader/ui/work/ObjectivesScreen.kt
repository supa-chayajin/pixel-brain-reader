package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.Milestone
import cloud.wafflecommons.pixelbrainreader.data.work.Objective
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveSettings
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ObjectivesViewModel @Inject constructor(
    private val repository: ObjectiveRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val settings: StateFlow<ObjectiveSettings> = repository.settings
    val visibility: StateFlow<Visibility> = modesRepository.visibility

    /** Visible objectives; null while loading. */
    val objectives: StateFlow<List<Objective>?> = combine(repository.changes, modesRepository.visibility) { _, v -> v }
        .mapLatest { v -> repository.objectives().filter { v.isVisible(it.note.path) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { repository.reloadSettings() }
    }

    fun toggle(objective: Objective, milestone: Milestone) = run("Couldn't update the milestone") { repository.toggle(objective, milestone) }

    fun updateSlot(transform: (ObjectiveSettings) -> ObjectiveSettings) = run("Couldn't save the slot") { repository.updateSettings(transform) }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$failure: ${e.message}")
            }
        }
    }
}

/** Objectives (#5): notes tagged #objectif, their milestones, the log as material, a weekly slot. */
@Composable
fun ObjectivesScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: ObjectivesViewModel = hiltViewModel()) {
    val objectives by viewModel.objectives.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    val today = LocalDate.now()

    FormScaffold(title = "Objectives", onBack = onBack, snackbarHostState = snackbarHostState) {
        SlotCard(settings, visibility.workLabel, viewModel::updateSlot)
        val list = objectives
        when {
            list == null -> EmptyHint("Reading your objectives…")
            list.isEmpty() && visibility.hideWork -> EmptyHint("Your objectives are hidden outside ${visibility.workLabel} mode.")
            list.isEmpty() -> EmptyHint(
                "No objective yet. Tag a note #objectif, list its milestones as checkboxes (a due date with 📅 2026-12-01), " +
                    "and give it the tags your log entries use: they become its material."
            )
            else -> list.forEach { objective ->
                ObjectiveCard(objective, today, onToggle = { m -> viewModel.toggle(objective, m) }, onOpen = { onOpenNote(objective.note.path) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotCard(settings: ObjectiveSettings, workLabel: String, update: ((ObjectiveSettings) -> ObjectiveSettings) -> Unit) {
    var pickTime by remember { mutableStateOf(false) }
    SectionCard(
        title = "Weekly slot",
        icon = Icons.Rounded.Schedule,
        subtitle = "Time kept for your objectives. When it starts, a nudge names what is due soon and what stalled — never outside $workLabel mode.",
        trailing = { Switch(checked = settings.slotEnabled, onCheckedChange = { on -> update { it.copy(slotEnabled = on) } }) }
    ) {
        if (settings.slotEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = settings.slotDay == day.value,
                        onClick = { update { it.copy(slotDay = day.value) } },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, androidx.compose.ui.platform.LocalLocale.current.platformLocale)) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { pickTime = true }, label = { Text("Starts ${settings.slotStart}") })
                listOf(60, 90, 120).forEach { minutes ->
                    FilterChip(selected = settings.slotMinutes == minutes, onClick = { update { it.copy(slotMinutes = minutes) } }, label = { Text("$minutes min") })
                }
            }
        }
    }
    if (pickTime) {
        PickTimeDialog(initial = settings.slotStart, onConfirm = { hhmm -> update { it.copy(slotStart = hhmm) }; pickTime = false }, onDismiss = { pickTime = false })
    }
}

@Composable
private fun ObjectiveCard(objective: Objective, today: LocalDate, onToggle: (Milestone) -> Unit, onOpen: () -> Unit) {
    var expanded by rememberSaveable(objective.note.path) { mutableStateOf(false) }
    val stale = objective.stale(today)
    SectionCard(
        title = objective.note.title,
        icon = Icons.Rounded.Flag,
        tint = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        subtitle = buildList {
            add(if (objective.milestones.isEmpty()) "No milestone yet" else "${objective.done} of ${objective.milestones.size} milestones")
            objective.nextDue?.due?.let { add("next due ${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}") }
            add(if (objective.material.size == 1) "1 log entry" else "${objective.material.size} log entries")
            if (stale) add("no progress for 3 weeks")
        }.joinToString(" · "),
        modifier = Modifier.clickable { expanded = !expanded },
        trailing = { TextButton(onClick = onOpen) { Text("Open") } }
    ) {
        if (objective.milestones.isNotEmpty()) {
            LinearProgressIndicator(progress = { objective.progress }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }
        if (expanded) {
            objective.milestones.forEach { milestone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = milestone.done, onCheckedChange = { onToggle(milestone) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(milestone.text, style = MaterialTheme.typography.bodyMedium)
                        milestone.due?.let { due ->
                            Text(
                                "due ${due.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!milestone.done && due < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (objective.material.isNotEmpty()) {
                Text("Material from the log", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                objective.material.sortedByDescending { it.at }.take(10).forEach { entry ->
                    Text(
                        "${entry.at.toLocalDate()} · ${entry.kind.label} · ${entry.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
