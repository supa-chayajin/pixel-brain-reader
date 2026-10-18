package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryMath
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryReadout
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryStatus
import cloud.wafflecommons.pixelbrainreader.data.health.SleepStages
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.ui.theme.ChartPalette
import cloud.wafflecommons.pixelbrainreader.ui.theme.SemanticPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecoveryUiState(
    val loading: Boolean = true,
    val readout: RecoveryReadout? = null,
    val needsPermission: Boolean = false
)

/** Last night's recovery against the user's own four-week norm; re-read when health metrics change. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val recoveryRepository: RecoveryRepository,
    healthMetricsRepository: HealthMetricsRepository,
    appClock: AppClock
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<RecoveryUiState> =
        combine(appClock.today, healthMetricsRepository.changes, refreshes) { today, _, _ -> today }
            .mapLatest { today ->
                val history = recoveryRepository.history(today, RecoveryMath.BASELINE_NIGHTS + 1)
                RecoveryUiState(
                    loading = false,
                    readout = RecoveryMath.readout(history.nights, today),
                    needsPermission = history.needsPermission
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecoveryUiState())

    fun permissions(): Set<String> = recoveryRepository.requiredPermissions()

    fun refresh() = refreshes.update { it + 1 }
}

@Composable
fun RecoveryCard(viewModel: RecoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { viewModel.refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recovery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("last night", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val readout = state.readout
            if (state.loading || readout == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                StatusLine(readout)
                if (readout.status != RecoveryStatus.NO_DATA) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric(
                            label = "Resting HR",
                            value = readout.night?.restingHeartRate?.let { "$it bpm" },
                            detail = readout.restingDelta?.let { delta ->
                                "${signed(delta.roundToInt())} vs ${readout.restingBaseline!!.mean.roundToInt()}"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Metric(
                            label = "HRV",
                            value = readout.night?.hrvMs?.let { "${it.roundToInt()} ms" },
                            detail = readout.hrvDeltaPercent?.let { pct ->
                                "${signed(pct.roundToInt())}% vs ${readout.hrvBaseline!!.mean.roundToInt()}"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Metric(
                            label = "Sleep",
                            value = readout.night?.sleepMinutes?.let(::hoursMinutes),
                            detail = readout.sleepBaseline?.let { "avg ${hoursMinutes(it.mean.roundToInt().toLong())}" },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                readout.night?.stages?.let { StagesBar(it) }
                val nights = maxOf(readout.restingBaseline?.nights ?: 0, readout.hrvBaseline?.nights ?: 0)
                if (nights > 0) {
                    Text(
                        "Compared with your own last ${RecoveryMath.BASELINE_NIGHTS} nights ($nights measured). " +
                            "More than one standard deviation from your norm counts as unusual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.needsPermission) {
                Text(
                    "Allow resting heart rate and heart rate variability in Health Connect to read your recovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = { permissionLauncher.launch(viewModel.permissions()) }) {
                    Text("Allow in Health Connect")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(readout: RecoveryReadout) {
    val (text, color) = when (readout.status) {
        RecoveryStatus.NO_DATA -> "No resting heart rate or HRV for last night" to MaterialTheme.colorScheme.onSurfaceVariant
        RecoveryStatus.BUILDING_BASELINE ->
            "Building your baseline: ${readout.nightsWithSignals} of ${RecoveryMath.MIN_BASELINE_NIGHTS} nights" to
                MaterialTheme.colorScheme.onSurfaceVariant
        RecoveryStatus.IN_RANGE -> "In your usual range" to SemanticPalette.Success
        RecoveryStatus.STRAINED -> "Below your usual recovery" to SemanticPalette.Warning
        RecoveryStatus.RESTED -> "Better recovered than usual" to SemanticPalette.Success
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Metric(label: String, value: String?, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "not measured", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StagesBar(stages: SleepStages) {
    val parts = listOf(
        Triple("Deep", stages.deep, ChartPalette.Sleep),
        Triple("REM", stages.rem, ChartPalette.Meditation),
        Triple("Light", stages.light, ChartPalette.Sleep.copy(alpha = 0.45f)),
        Triple("Awake", stages.awake, ChartPalette.Calories)
    ).filter { it.second > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            parts.forEach { (_, minutes, color) ->
                Box(Modifier.weight(minutes.toFloat()).fillMaxHeight().background(color))
            }
        }
        Text(
            parts.joinToString("  ·  ") { (label, minutes, _) -> "$label ${hoursMinutes(minutes)}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${abs(value)}"
    else -> "±0"
}

private fun hoursMinutes(minutes: Long): String =
    if (minutes >= 60) String.format(Locale.ROOT, "%dh%02d", minutes / 60, minutes % 60) else "${minutes}m"
