package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.LoadComparison
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecovery
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryReport
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class LoadRecoveryViewModel @Inject constructor(
    private val repository: LoadRecoveryRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _report = MutableStateFlow<LoadRecoveryReport?>(null)
    val report: StateFlow<LoadRecoveryReport?> = _report.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            try {
                _report.value = repository.report(force)
                _failed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.value = true
            }
        }
    }
}

private fun LoadComparison.label(): String = when (metric) {
    "sleep" -> "Sleep the next night"
    "hrv" -> "HRV the next night"
    "resting" -> "Resting HR the next night"
    else -> "Mood that day"
}

private fun LoadComparison.format(value: Double): String = when (metric) {
    "sleep" -> durationLabel(value.roundToInt())
    "hrv" -> "${value.roundToInt()} ms"
    "resting" -> "${value.roundToInt()} bpm"
    else -> "%.1f / 5".format(value)
}

/** Load vs recovery (#7): eight weeks of work days against sleep, HRV, resting heart rate and mood. */
@Composable
fun LoadRecoveryScreen(onBack: () -> Unit, viewModel: LoadRecoveryViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    FormScaffold(title = "Load vs recovery", onBack = onBack) {
        if (visibility.hideWork || visibility.hideHealth) {
            EmptyHint("Hidden outside Work mode and while screen-share safe is on.")
            return@FormScaffold
        }
        val r = report
        when {
            failed -> EmptyHint("Couldn't read the data.")
            r == null -> EmptyHint("Reading eight weeks of work and recovery…")
            else -> {
                r.overload?.let { OverloadCard(it) } ?: SectionCard(
                    title = "No overload signal",
                    icon = Icons.Rounded.MonitorHeart,
                    subtitle = "A signal shows when the last seven days carry at least 30% more meetings and off-ticket time than your usual week " +
                        "and a recovery measure is below your own norm."
                )
                SectionCard(
                    title = "After heavy and light work days",
                    icon = Icons.Rounded.MonitorHeart,
                    subtitle = "${r.workDays} work days in eight weeks, ${r.loggedDays} with meetings or log entries. Heavy days are above your median load " +
                        "(meeting time + off-ticket time). A correlation is not a cause."
                ) {
                    if (r.loggedDays < LoadRecovery.MIN_DAYS) {
                        Text(
                            "Not enough logged days yet (${r.loggedDays} of ${LoadRecovery.MIN_DAYS}). Log meetings with their length and off-ticket time with minutes.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        r.comparisons.forEach { ComparisonRow(it) }
                    }
                }
                if (r.needsHealthPermission) {
                    Text(
                        "Resting heart rate and HRV need their Health Connect access: allow it from the Recovery card in Life Stats.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { viewModel.load(force = true) }) { Text("Recompute") }
            }
        }
    }
}

@Composable
internal fun OverloadCard(signal: OverloadSignal) {
    SectionCard(
        title = "Heavy week, low recovery",
        icon = Icons.Rounded.Warning,
        tint = MaterialTheme.colorScheme.error,
        subtitle = "${durationLabel(signal.weekMinutes)} of meetings and off-ticket time in the last 7 days, " +
            "+${signal.loadIncreasePercent}% on your usual ${durationLabel(signal.usualWeekMinutes)}."
    ) {
        signal.reasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ComparisonRow(comparison: LoadComparison) {
    androidx.compose.foundation.layout.Column {
        Text(comparison.label(), style = MaterialTheme.typography.labelLarge)
        if (comparison.pairs < LoadRecovery.MIN_DAYS) {
            Text("Not measured enough (${comparison.pairs} days).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Heavy: " + (comparison.heavyMean?.let { comparison.format(it) } ?: "–") + " (n = ${comparison.heavyDays})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Light: " + (comparison.lightMean?.let { comparison.format(it) } ?: "–") + " (n = ${comparison.lightDays})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        comparison.correlation?.let { r ->
            Text(
                "Correlation with load: r = ${"%.2f".format(r)} (${LoadRecovery.strength(r)}, ${comparison.pairs} days)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
