package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffFormat
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
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
import javax.inject.Inject

@HiltViewModel
class HandoffViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val handoffRepository: DeskHandoffRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<ProjectSummary>?>(null)
    /** The visible projects, most recently active first; null while loading. */
    val projects: StateFlow<List<ProjectSummary>?> = _projects.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            _projects.value = try {
                projectRepository.projects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Appends the hand-off to the project's `Handoff.md` (read from disk first), then [onSent]. */
    fun send(project: ProjectSummary, kind: String?, text: String, onSent: () -> Unit) {
        if (_sending.value || text.isBlank()) return
        _sending.value = true
        viewModelScope.launch {
            try {
                handoffRepository.add(project.root, project.title, DeskHandoffFormat.typed(kind, text))
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Couldn't save the hand-off: ${e.message}")
            } finally {
                _sending.value = false
            }
        }
    }
}

/** The kinds offered when handing off: English labels over the French prefixes the desktop reads. */
private val KIND_LABELS: List<Pair<String?, String>> =
    listOf<Pair<String?, String>>(null to "Note") + DeskHandoffFormat.KINDS.zip(listOf("Task", "Question", "Decision"))

/**
 * Hand off to desktop (C13), from anywhere: an open note ([initialText] links it), the share
 * sheet, the Projects widget. One line in the chosen project's `Handoff.md`, optionally typed.
 */
@Composable
fun HandoffScreen(
    initialRoot: String?,
    initialText: String,
    onBack: () -> Unit,
    onSent: () -> Unit,
    viewModel: HandoffViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

    var root by rememberSaveable { mutableStateOf(initialRoot?.takeIf { it.isNotBlank() }) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val list = projects
    // No project given (or it is hidden): the most recently active one.
    val chosen = list?.firstOrNull { it.root == root } ?: list?.firstOrNull()

    FormScaffold(title = "Hand off to desktop", onBack = onBack, snackbarHostState = snackbarHostState) {
        when {
            list == null -> EmptyHint("Reading your projects…")
            list.isEmpty() -> EmptyHint("No project to hand off to: a project is a folder under 20_Projects/.")
            else -> {
                SectionCard(title = "Project", icon = Icons.Rounded.Computer, subtitle = "Shown when your next desktop session on it starts.") {
                    list.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) { root = p.root }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = chosen?.root == p.root, onClick = { root = p.root })
                            Text(p.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KIND_LABELS.forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("For your next session") },
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !sending && chosen != null && text.isNotBlank(), onClick = { chosen?.let { viewModel.send(it, kind, text, onSent) } }) {
                        Text("Hand off")
                    }
                    if (sending) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                Text(
                    "One line in ${chosen?.name ?: "the project"}'s Handoff.md, pushed with your next batch. " +
                        "A typed hand-off starts with ${DeskHandoffFormat.KINDS.joinToString(", ") { "$it:" }}, which the desktop can group by.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
