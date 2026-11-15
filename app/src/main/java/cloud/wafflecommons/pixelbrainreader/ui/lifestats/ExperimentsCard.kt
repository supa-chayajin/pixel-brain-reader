package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.experiments.Experiment
import cloud.wafflecommons.pixelbrainreader.data.experiments.ExperimentRepository
import cloud.wafflecommons.pixelbrainreader.data.experiments.Experiments
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** Personal experiments (L2, 12.21): re-read each day and after each change; null while reading. */
@HiltViewModel
class ExperimentsViewModel @Inject constructor(
    private val repository: ExperimentRepository,
    habitRepository: HabitRepository,
    private val appClock: AppClock
) : ViewModel() {
    /** Today, from the clock: a screen left open past midnight moves on with it. */
    val today: StateFlow<LocalDate> = appClock.today
    private val _standings = MutableStateFlow<List<ExperimentRepository.Standing>?>(null)
    val standings: StateFlow<List<ExperimentRepository.Standing>?> = _standings.asStateFlow()
    private val refresh = MutableStateFlow(0)

    /** The habits an experiment can follow; archived ones left out. */
    val habits: StateFlow<List<HabitConfig>> = habitRepository.getHabitConfigsFlow()
        .map { list -> list.filterNot { it.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(appClock.today, refresh) { today, _ -> today }.collectLatest { today ->
                _standings.value = try {
                    repository.standings(today)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    fun start(hypothesis: String, habitId: String, outcome: Experiments.Outcome) {
        if (VaultWriteGate.refuses()) return
        val today = appClock.today.value
        val experiment = Experiment(
            id = UUID.randomUUID().toString(),
            hypothesis = hypothesis.trim(),
            habitId = habitId,
            outcome = outcome.key,
            start = today.toString()
        )
        viewModelScope.launch {
            repository.update { it.copy(experiments = it.experiments + experiment) }
            refresh.value++
        }
    }

    fun remove(id: String) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            repository.update { file -> file.copy(experiments = file.experiments.filterNot { it.id == id }) }
            refresh.value++
        }
    }
}

/**
 * Does doing X change Y? (L2, the owner's D2–D3): each experiment's phase, how often the habit was
 * done during the trial, and once it is over, the difference with its 95% interval in plain words.
 * No result before the end: looking along the way invites stopping on a lucky day.
 */
@Composable
fun ExperimentsCard(viewModel: ExperimentsViewModel = hiltViewModel()) {
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Experiment?>(null) }
    val readOnly = VaultWriteGate.readOnly

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Experiments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!readOnly) {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("New", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            when (val list = standings) {
                null -> Text("Reading your days…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    if (list.isEmpty()) {
                        Text(
                            "Does something you do change your mood, sleep, HRV or tasks? Try it: ${Experiments.BASELINE_DAYS} days as usual, " +
                                "then ${Experiments.TRIAL_DAYS} days doing it, a habit you tick.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    list.forEachIndexed { index, standing ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                        ExperimentRow(standing, onRemove = if (readOnly) null else { { removing = standing.experiment } })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "A hint, not proof: you knew you were trying it, and one day leans on the next.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (creating) {
        NewExperimentDialog(
            habits = habits,
            today = today,
            onStart = { hypothesis, habitId, outcome ->
                viewModel.start(hypothesis, habitId, outcome)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
    removing?.let { experiment ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this experiment?") },
            text = { Text("\"${experiment.hypothesis}\" leaves the list. Your habit and your days stay as they are.") },
            confirmButton = { TextButton(onClick = { viewModel.remove(experiment.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

@Composable
private fun ExperimentRow(standing: ExperimentRepository.Standing, onRemove: (() -> Unit)?) {
    val experiment = standing.experiment
    val outcome = standing.outcome
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(experiment.hypothesis, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOfNotNull(
                "Doing: " + (standing.habitTitle ?: "a removed habit"),
                outcome?.let { "watching: ${it.label.lowercase(Locale.ENGLISH)}, ${it.source}" }
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val result = standing.result
        val phaseLine = when (standing.phase) {
            Experiments.Phase.BASELINE ->
                "As usual · day ${standing.dayOfPhase} of ${experiment.baselineDays} · the trial starts " +
                    Experiments.trialStart(experiment)?.format(DAY)
            Experiments.Phase.TRIAL ->
                "Trial · day ${standing.dayOfPhase} of ${experiment.trialDays}" +
                    (result?.let { " · done on ${it.trialDaysDone} of ${it.trialDaysSoFar} days" } ?: "") +
                    " · the result on " + Experiments.resultDay(experiment)?.format(DAY)
            Experiments.Phase.DONE -> result?.let { "Done on ${it.trialDaysDone} of ${it.trialDaysSoFar} trial days" }
            null -> "Its start date doesn't read"
        }
        phaseLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (standing.phase == Experiments.Phase.DONE && result != null && outcome != null) {
            val verdict = result.verdict
            Text(
                Experiments.words(outcome, result),
                style = MaterialTheme.typography.bodyMedium,
                color = if (verdict == Experiments.Verdict.HIGHER || verdict == Experiments.Verdict.LOWER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result.baselineMean != null && result.trialMean != null) {
                Text(
                    "Before ${mean(outcome, result.baselineMean)} (${result.baselineN} days) · during ${mean(outcome, result.trialMean)} (${result.trialN} days)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) { Text("Remove") }
        }
    }
}

/** A mean in the outcome's own unit. */
private fun mean(outcome: Experiments.Outcome, value: Double): String = when (outcome) {
    Experiments.Outcome.MOOD -> String.format(Locale.ROOT, "%.1f", value)
    Experiments.Outcome.SLEEP -> value.roundToInt().let { minutes -> "${minutes / 60} h " + String.format(Locale.ROOT, "%02d", minutes % 60) }
    Experiments.Outcome.HRV -> "${value.roundToInt()} ms"
    Experiments.Outcome.TASKS -> "${(value * 100).roundToInt()}%"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewExperimentDialog(
    habits: List<HabitConfig>,
    today: LocalDate,
    onStart: (String, String, Experiments.Outcome) -> Unit,
    onDismiss: () -> Unit
) {
    var hypothesis by rememberSaveable { mutableStateOf("") }
    var habitId by rememberSaveable { mutableStateOf<String?>(null) }
    var outcomeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val outcome = outcomeKey?.let { Experiments.Outcome.of(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New experiment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hypothesis,
                    onValueChange = { hypothesis = it },
                    label = { Text("What do you expect?") },
                    placeholder = { Text("No screens after 22:00 helps me sleep") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (habits.isEmpty()) {
                    Text("Add the habit you'll tick in Habits first.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = habits.firstOrNull { it.id == habitId }?.title ?: "",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text("The habit you'll tick") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            habits.forEach { habit ->
                                DropdownMenuItem(text = { Text(habit.title) }, onClick = { habitId = habit.id; expanded = false })
                            }
                        }
                    }
                }
                Text("What may change", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Experiments.Outcome.entries.forEach { option ->
                        FilterChip(selected = option == outcome, onClick = { outcomeKey = option.key }, label = { Text(option.label) })
                    }
                }
                outcome?.let { Text("Each day counts ${it.source}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val trial = today.plusDays(Experiments.BASELINE_DAYS.toLong())
                val result = trial.plusDays(Experiments.TRIAL_DAYS.toLong())
                Text(
                    "As usual until ${trial.minusDays(1).format(DAY)}, then the trial until ${result.minusDays(1).format(DAY)}: the result on ${result.format(DAY)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(hypothesis, habitId!!, outcome!!) },
                enabled = hypothesis.isNotBlank() && habitId != null && outcome != null
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
