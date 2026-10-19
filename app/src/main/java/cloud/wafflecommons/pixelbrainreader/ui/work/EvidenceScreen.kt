package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceFigures
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceRepository
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.ReportPeriod
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EvidenceViewModel @Inject constructor(
    private val repository: EvidenceRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _period = MutableStateFlow<ReportPeriod>(ReportPeriod.Month(YearMonth.now()))
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()

    /** The period's figures; null while loading or when the log can't be read. */
    val figures: StateFlow<EvidenceFigures?> = _period
        .mapLatest { period ->
            try {
                repository.figures(period)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun setQuarterly(quarterly: Boolean) {
        val anchor = _period.value.start
        _period.value = if (quarterly) ReportPeriod.Quarter.of(anchor) else ReportPeriod.Month(YearMonth.from(anchor))
    }

    fun previous() {
        _period.value = _period.value.previous()
    }

    fun next() {
        _period.value = _period.value.next()
    }

    fun write(withSummary: Boolean, open: (String) -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                open(repository.write(_period.value, withSummary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Couldn't write the report: ${e.message}")
            } finally {
                _working.value = false
            }
        }
    }
}

private fun ReportPeriod.label(): String = when (this) {
    is ReportPeriod.Month -> start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    is ReportPeriod.Quarter -> "Q$quarter $year"
}

/** The evidence report (#2): a month or a quarter of the mission log, as a French draft. */
@Composable
fun EvidenceScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: EvidenceViewModel = hiltViewModel()) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val figures by viewModel.figures.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    var withSummary by remember { mutableStateOf(false) }

    FormScaffold(title = "Evidence report", onBack = onBack, snackbarHostState = snackbarHostState) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(false to "Month", true to "Quarter").forEachIndexed { index, (quarterly, label) ->
                SegmentedButton(
                    selected = (period is ReportPeriod.Quarter) == quarterly,
                    onClick = { viewModel.setQuarterly(quarterly) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(label) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = viewModel::previous) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous") }
            Text(period.label(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::next) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next")
            }
        }
        SectionCard(
            title = "What the log shows",
            icon = Icons.Rounded.Summarize,
            subtitle = "${period.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))} – " +
                period.end.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        ) {
            val f = figures
            when {
                visibility.hideWork -> Text("Hidden outside ${visibility.workLabel} mode.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f == null -> Text("The mission log couldn't be read.", style = MaterialTheme.typography.bodyMedium)
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Figure("Facts", f.count(LogKind.FACT).toString())
                    Figure("Decisions", f.count(LogKind.DECISION).toString())
                    Figure("Requests", "${f.count(LogKind.REQUEST)} (${f.openRequests} open)")
                    Figure("Refusals", f.count(LogKind.REFUSAL).toString())
                    Figure("Off-ticket time", f.offTicketMinutes?.let(::durationLabel) ?: "not measured")
                    Figure("Work completed", "${f.completed.size} notes")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Add a summary", style = MaterialTheme.typography.bodyLarge)
                Text("Written on the device by Gemini Nano, marked as such.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = withSummary, onCheckedChange = { withSummary = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !working && figures != null, onClick = { viewModel.write(withSummary, onOpenNote) }) { Text("Write the report") }
            if (working) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Text(
            "A French draft in your inbox: figures by kind, role and tag, the decisions, requests and refusals, and the notes " +
                "moved to done in your work folders. Every number comes from the log.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
