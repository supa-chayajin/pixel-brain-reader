package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs
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
 * Does doing X change Y? (L2, the owner's D2–D3), in Life Stats' one style (D7): each experiment a
 * tile with its two phases as a bar, and once it is over the difference with its 95% interval
 * drawn around zero. No result before the end: looking along the way invites stopping on a lucky
 * day.
 */
@Composable
fun ExperimentsCard(viewModel: ExperimentsViewModel = hiltViewModel()) {
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Experiment?>(null) }
    val readOnly = VaultWriteGate.readOnly

    StatsCard(
        title = "Experiments",
        icon = Icons.Rounded.Science,
        accent = MaterialTheme.colorScheme.secondary,
        action = if (readOnly) null else {
            { FilledTonalIconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, contentDescription = "New experiment") } }
        }
    ) {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { standing ->
                        ExperimentTile(standing, onRemove = if (readOnly) null else { { removing = standing.experiment } })
                    }
                }
            }
        }
        Text(
            "A hint, not proof: you knew you were trying it, and one day leans on the next.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentTile(standing: ExperimentRepository.Standing, onRemove: (() -> Unit)?) {
    val experiment = standing.experiment
    val outcome = standing.outcome
    val result = standing.result
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(experiment.hypothesis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelChip("Doing: " + (standing.habitTitle ?: "a removed habit"))
                outcome?.let { LabelChip("Watching: " + it.label.lowercase(Locale.ENGLISH)) }
            }
            PhaseBar(standing)
            val line = when (standing.phase) {
                Experiments.Phase.BASELINE ->
                    "As usual · day ${standing.dayOfPhase} of ${experiment.baselineDays} · the trial starts " +
                        Experiments.trialStart(experiment)?.format(DAY)
                Experiments.Phase.TRIAL ->
                    "Doing it · day ${standing.dayOfPhase} of ${experiment.trialDays}" +
                        (result?.let { " · done on ${it.trialDaysDone} of ${it.trialDaysSoFar}" } ?: "") +
                        " · the result on " + Experiments.resultDay(experiment)?.format(DAY)
                Experiments.Phase.DONE -> result?.let { "Done on ${it.trialDaysDone} of ${it.trialDaysSoFar} trial days" }
                null -> "Its start date doesn't read"
            }
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (standing.phase == Experiments.Phase.DONE && result != null && outcome != null) {
                ResultBlock(outcome, result)
            } else {
                outcome?.let { Text("Each day counts ${it.source}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** The two phases side by side, each filled as far as its days have gone. */
@Composable
private fun PhaseBar(standing: ExperimentRepository.Standing) {
    val experiment = standing.experiment
    val day = (standing.dayOfPhase ?: 1) - 1
    val (baseline, trial) = when (standing.phase) {
        Experiments.Phase.BASELINE -> day.toFloat() / experiment.baselineDays to 0f
        Experiments.Phase.TRIAL -> 1f to day.toFloat() / experiment.trialDays
        Experiments.Phase.DONE -> 1f to 1f
        null -> 0f to 0f
    }
    // On the tile's surfaceContainerHigh, the outline variant still shows an empty phase.
    val track = MaterialTheme.colorScheme.outlineVariant
    val usual = MaterialTheme.colorScheme.outline
    val doing = readableOn(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.surfaceContainerHigh)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Segment(baseline, usual, track, Modifier.weight(experiment.baselineDays.toFloat()))
            Segment(trial, doing, track, Modifier.weight(experiment.trialDays.toFloat()))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("As usual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(experiment.baselineDays.toFloat()))
            Text("Doing it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(experiment.trialDays.toFloat()))
        }
    }
}

@Composable
private fun Segment(fraction: Float, fill: Color, track: Color, modifier: Modifier) {
    Box(modifier = modifier.height(8.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(fill))
    }
}

/** The difference in big, its 95% interval drawn around zero, and the words. */
@Composable
private fun ResultBlock(outcome: Experiments.Outcome, result: Experiments.Result) {
    val verdict = result.verdict
    val clear = verdict == Experiments.Verdict.HIGHER || verdict == Experiments.Verdict.LOWER
    val accent = if (clear) {
        readableOn(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val difference = result.difference
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (difference != null) {
            Text(Experiments.amount(outcome, difference), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
        }
        val low = result.low
        val high = result.high
        if (difference != null && low != null && high != null) IntervalBar(low, high, difference, accent)
        Text(Experiments.words(outcome, result), style = MaterialTheme.typography.bodyMedium, color = accent)
        if (result.baselineMean != null && result.trialMean != null) {
            Text(
                "Before ${mean(outcome, result.baselineMean)} (${result.baselineN} days) · during ${mean(outcome, result.trialMean)} (${result.trialN} days)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The 95% interval as a band on an axis centred on zero, the difference as a dot. */
@Composable
private fun IntervalBar(low: Double, high: Double, difference: Double, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val zero = MaterialTheme.colorScheme.outline
    val reach = maxOf(abs(low), abs(high), abs(difference)).takeIf { it > 0 }?.times(1.2) ?: 1.0
    Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
        fun x(value: Double) = (size.width / 2) + (value / reach).toFloat() * (size.width / 2 - size.height / 2)
        val y = size.height / 2
        val bar = 6.dp.toPx()
        drawRoundRect(track, topLeft = Offset(0f, y - bar / 2), size = Size(size.width, bar), cornerRadius = CornerRadius(bar / 2))
        drawLine(zero, Offset(size.width / 2, 1f), Offset(size.width / 2, size.height - 1f), strokeWidth = 1.5.dp.toPx())
        drawRoundRect(
            color.copy(alpha = 0.45f),
            topLeft = Offset(x(low), y - bar / 2),
            size = Size(x(high) - x(low), bar),
            cornerRadius = CornerRadius(bar / 2)
        )
        drawCircle(color, radius = size.height / 2, center = Offset(x(difference), y))
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
