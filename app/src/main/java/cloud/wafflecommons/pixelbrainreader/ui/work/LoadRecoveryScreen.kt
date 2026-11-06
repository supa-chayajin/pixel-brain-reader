package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
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
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeed
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeedRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.CoachSeeds
import cloud.wafflecommons.pixelbrainreader.data.work.LoadBreakdown
import cloud.wafflecommons.pixelbrainreader.data.work.LoadComparison
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecovery
import cloud.wafflecommons.pixelbrainreader.data.work.LoadShare
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
    private val chatSeeds: ChatSeedRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _report = MutableStateFlow<LoadRecoveryReport?>(null)
    val report: StateFlow<LoadRecoveryReport?> = _report.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Prepares the Coach's triage of this week's load; the screen then opens the chat. */
    fun coach(report: LoadRecoveryReport) {
        chatSeeds.offer(ChatSeed(CoachSeeds.COACH_PRESET, CoachSeeds.load(report)))
    }

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
fun LoadRecoveryScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: LoadRecoveryViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    FormScaffold(title = "Load vs recovery", onBack = onBack) {
        if (visibility.hideWork || visibility.hideHealth) {
            EmptyHint("Hidden outside ${visibility.workLabel} mode and while screen-share safe is on.")
            return@FormScaffold
        }
        val r = report
        when {
            failed -> EmptyHint("Couldn't read the data.")
            r == null -> EmptyHint("Reading eight weeks of work and recovery…")
            else -> {
                r.overload?.let { OverloadCard(it, r.breakdown) } ?: SectionCard(
                    title = "No overload signal",
                    icon = Icons.Rounded.MonitorHeart,
                    subtitle = "A signal shows when the last seven days carry at least 30% more meetings and off-ticket time than your usual week " +
                        "and a recovery measure is below your own norm."
                )
                LoadSourcesCard(r.breakdown, onOpenLog, onOpenActions, onCoach = { viewModel.coach(r); onOpenChat() })
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
internal fun OverloadCard(signal: OverloadSignal, breakdown: LoadBreakdown? = null) {
    SectionCard(
        title = "Heavy week, low recovery",
        icon = Icons.Rounded.Warning,
        tint = MaterialTheme.colorScheme.error,
        subtitle = "${durationLabel(signal.weekMinutes)} of meetings and off-ticket time in the last 7 days, " +
            "+${signal.loadIncreasePercent}% on your usual ${durationLabel(signal.usualWeekMinutes)}."
    ) {
        signal.reasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.bodyMedium) }
        // Where it comes from, in one or two lines: the heaviest role and requester, what's still open.
        breakdown?.byRole?.firstOrNull()?.let { Text("• Most of it as ${it.name}: ${it.line()}", style = MaterialTheme.typography.bodyMedium) }
        breakdown?.byRequester?.firstOrNull()?.let { Text("• Most asked by ${it.name}: ${it.line()}", style = MaterialTheme.typography.bodyMedium) }
        breakdown?.takeIf { it.openRequests.isNotEmpty() || it.overdueMyActions > 0 }?.let { b ->
            Text("• ${openLine(b)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Where this week's load comes from: roles, requesters, open requests and overdue actions, all from the log. */
@Composable
private fun LoadSourcesCard(breakdown: LoadBreakdown, onOpenLog: () -> Unit, onOpenActions: () -> Unit, onCoach: () -> Unit) {
    SectionCard(
        title = "Where this week's load comes from",
        icon = Icons.Rounded.Insights,
        subtitle = if (breakdown.isEmpty) "Nothing in the log for the last 7 days carries a role or a requester." else "The last 7 days of the mission log."
    ) {
        if (breakdown.byRole.isNotEmpty()) {
            Text("By role", style = MaterialTheme.typography.labelLarge)
            breakdown.byRole.forEach { Text("${it.name} · ${it.line()}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (breakdown.byRequester.isNotEmpty()) {
            Text("By requester", style = MaterialTheme.typography.labelLarge)
            breakdown.byRequester.forEach { Text("${it.name} · ${it.line()}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (breakdown.openRequests.isNotEmpty() || breakdown.overdueMyActions > 0) {
            Text(openLine(breakdown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.FlowRow {
            TextButton(onClick = onOpenLog) { Text("Open the log") }
            TextButton(onClick = onOpenActions) { Text("Your actions") }
            TextButton(onClick = onCoach) { Text("Triage with Coach") }
        }
    }
}

private fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

/** "1 h 30 off-ticket · 2 requests (1 open) · 1 refusal". */
private fun LoadShare.line(): String = buildList {
    offTicketMinutes?.let { add("${durationLabel(it)} off-ticket") }
    if (requests > 0) add(plural(requests, "request") + if (openRequests > 0) " ($openRequests open)" else "")
    if (refusals > 0) add(plural(refusals, "refusal"))
}.joinToString(" · ").ifEmpty { "logged, without minutes or requests" }

private fun openLine(b: LoadBreakdown): String = buildList {
    if (b.openRequests.isNotEmpty()) add("${plural(b.openRequests.size, "request")} still open")
    if (b.overdueMyActions > 0) add("${plural(b.overdueMyActions, "action")} of yours overdue")
}.joinToString(", ")

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
