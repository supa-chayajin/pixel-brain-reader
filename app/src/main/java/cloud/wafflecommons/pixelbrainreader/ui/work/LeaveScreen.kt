package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.work.LeaveRepository
import cloud.wafflecommons.pixelbrainreader.data.work.LeaveReturn
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateRangeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class LeaveViewModel @Inject constructor(
    private val leaveRepository: LeaveRepository,
    private val modesRepository: ModesRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _returning = MutableStateFlow<LeaveReturn?>(null)
    val returning: StateFlow<LeaveReturn?> = _returning.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The next leave on the calendar (or the current one), to prefill the handover period. */
    val nextLeave: Pair<LocalDate, LocalDate>?
        get() = modesRepository.config.value.leavePeriods
            .mapNotNull { leave ->
                runCatching { LocalDate.parse(leave.start) to LocalDate.parse(leave.end) }.getOrNull()
            }
            .filter { (_, end) -> !end.isBefore(LocalDate.now()) }
            .minByOrNull { it.first }

    val defaultScope: String? get() = modesRepository.config.value.workFolders.firstOrNull()

    fun load() {
        viewModelScope.launch {
            _folders.value = vaultDiscoveryRepository.getAllFolderPaths()
                .filter { path -> !path.startsWith(".") && !path.startsWith("99_") && path.count { it == '/' } <= 2 }
                .sorted()
        }
        loadReturn()
    }

    /** Only the leave that just ended (cheap when there is none: no git walk). */
    fun loadReturn() {
        viewModelScope.launch {
            _returning.value = try {
                leaveRepository.recentReturn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun createHandover(scope: String, start: LocalDate, end: LocalDate, withSummary: Boolean, open: (String) -> Unit) =
        work("Couldn't write the handover") { open(leaveRepository.createHandover(scope, start, end, withSummary)) }

    fun createBriefing(withSummary: Boolean, open: (String) -> Unit) = work("Couldn't write the briefing") {
        val path = leaveRepository.createBriefing(withSummary)
        if (path == null) _messages.send("No leave ended in the last days.") else open(path)
    }

    private fun work(failure: String, block: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$failure: ${e.message}")
            } finally {
                _working.value = false
            }
        }
    }
}

private fun LocalDate.medium(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Leave & handover (#3): a handover draft before leaving, a briefing when back. */
@Composable
fun LeaveScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onManageLeave: () -> Unit,
    viewModel: LeaveViewModel = hiltViewModel()
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val returning by viewModel.returning.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var scope by remember { mutableStateOf(viewModel.defaultScope) }
    var period by remember { mutableStateOf(viewModel.nextLeave) }
    var withSummary by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var pickPeriod by remember { mutableStateOf(false) }

    FormScaffold(title = "Leave & handover", onBack = onBack, snackbarHostState = snackbarHostState) {
        if (visibility.hideWork) {
            Text(
                "Outside Work mode. The documents are still written to your inbox, to read in Work mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard(
            title = "Before you leave",
            icon = Icons.Rounded.Flight,
            subtitle = "A handover draft in your inbox, in French: work in progress and paused in the scope, the last month's " +
                "decisions, open requests and refusals from the mission log, and the key dates during your leave. Review it before sharing."
        ) {
            Text("Scope", style = MaterialTheme.typography.labelLarge)
            Box {
                AssistChip(onClick = { scopeMenu = true }, label = { Text(scope ?: "Choose a folder") })
                DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                    folders.forEach { folder ->
                        DropdownMenuItem(text = { Text(folder) }, onClick = { scope = folder; scopeMenu = false })
                    }
                }
            }
            Text("Leave", style = MaterialTheme.typography.labelLarge)
            AssistChip(
                onClick = { pickPeriod = true },
                label = { Text(period?.let { (start, end) -> "${start.medium()} – ${end.medium()}" } ?: "Choose the dates") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add a summary", style = MaterialTheme.typography.bodyLarge)
                    Text("Written on the device by Gemini Nano, marked as such.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = withSummary, onCheckedChange = { withSummary = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !working && scope != null && period != null,
                    onClick = {
                        val (start, end) = period ?: return@Button
                        viewModel.createHandover(scope ?: return@Button, start, end, withSummary, onOpenNote)
                    }
                ) { Text("Write the handover") }
                if (working) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            TextButton(onClick = onManageLeave) { Text("Manage leave periods") }
        }
        SectionCard(
            title = "Back from leave",
            icon = Icons.Rounded.BeachAccess,
            subtitle = returning?.let { r ->
                "You were away from ${r.start.medium()} to ${r.end.medium()}: ${r.changed} work notes changed and ${r.created} were created meanwhile."
            } ?: "In the ${cloud.wafflecommons.pixelbrainreader.data.work.LeaveDocuments.RETURN_WINDOW_DAYS} days after a leave, a briefing of what changed in your work folders shows here and on the Daily screen."
        ) {
            if (returning != null) {
                FilledTonalButton(enabled = !working, onClick = { viewModel.createBriefing(withSummary, onOpenNote) }) { Text("Brief me") }
            }
        }
    }

    if (pickPeriod) {
        PickDateRangeDialog(
            onConfirm = { start, end -> period = start to end; pickPeriod = false },
            onDismiss = { pickPeriod = false }
        )
    }
}

/** The Daily screen's welcome-back card: shown in the days after a leave, in Work mode only. */
@Composable
fun BackFromLeaveCard(onOpenNote: (String) -> Unit, viewModel: LeaveViewModel = hiltViewModel()) {
    val returning by viewModel.returning.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val r = returning ?: return
    if (visibility.hideWork) return
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Welcome back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "While you were away: ${r.changed} work notes changed, ${r.created} new.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            FilledTonalButton(enabled = !working, onClick = { viewModel.createBriefing(false, onOpenNote) }) { Text("Brief me") }
        }
    }
}
