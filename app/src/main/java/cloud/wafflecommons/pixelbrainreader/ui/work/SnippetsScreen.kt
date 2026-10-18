package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlock
import cloud.wafflecommons.pixelbrainreader.data.work.RunbookStep
import cloud.wafflecommons.pixelbrainreader.data.work.SnippetRepository
import cloud.wafflecommons.pixelbrainreader.data.work.StepResult
import cloud.wafflecommons.pixelbrainreader.data.work.StepStatus
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberCopyToClipboard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class SnippetsViewModel @Inject constructor(private val repository: SnippetRepository) : ViewModel() {

    private val _blocks = MutableStateFlow<List<Pair<CodeBlock, String>>?>(null)
    val blocks: StateFlow<List<Pair<CodeBlock, String>>?> = _blocks.asStateFlow()

    private val _runbooks = MutableStateFlow<List<WorkNote>?>(null)
    val runbooks: StateFlow<List<WorkNote>?> = _runbooks.asStateFlow()

    private val _runbook = MutableStateFlow<Pair<WorkNote, List<RunbookStep>>?>(null)
    val runbook: StateFlow<Pair<WorkNote, List<RunbookStep>>?> = _runbook.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun loadBlocks() {
        viewModelScope.launch { _blocks.value = repository.codeBlocks() }
    }

    fun loadRunbooks() {
        viewModelScope.launch { _runbooks.value = repository.runbooks() }
    }

    fun loadRunbook(path: String) {
        viewModelScope.launch { _runbook.value = repository.runbook(path) }
    }

    fun finish(runbook: WorkNote, startedAt: LocalDateTime, results: List<StepResult>, notes: String?, open: (String) -> Unit) {
        viewModelScope.launch {
            try {
                open(repository.writeIncident(runbook, startedAt, results, notes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Couldn't write the incident log: ${e.message}")
            }
        }
    }
}

/** The vault's code blocks (E4): search, filter by language, copy, open. */
@Composable
fun SnippetsScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, onOpenRunbooks: () -> Unit, viewModel: SnippetsViewModel = hiltViewModel()) {
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadBlocks() }
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<String?>(null) }
    val copy = rememberCopyToClipboard()

    FormScaffold(title = "Snippets", onBack = onBack, actions = { TextButton(onClick = onOpenRunbooks) { Text("Runbooks") } }) {
        val all = blocks
        if (all == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@FormScaffold
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val languages = all.map { it.first.language.ifEmpty { "text" } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            languages.forEach { (lang, count) ->
                FilterChip(selected = language == lang, onClick = { language = if (language == lang) null else lang }, label = { Text("$lang $count") })
            }
        }
        val shown = all.filter { (block, title) ->
            (language == null || block.language.ifEmpty { "text" } == language) &&
                (query.isBlank() || block.code.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true))
        }
        if (shown.isEmpty()) EmptyHint(if (all.isEmpty()) "No fenced code block in the vault yet." else "Nothing matches.")
        shown.take(MAX_SHOWN).forEach { (block, title) -> SnippetCard(block, title, onCopy = { copy(block.code) }, onOpen = { onOpenNote(block.path) }) }
        if (shown.size > MAX_SHOWN) Text("${shown.size - MAX_SHOWN} more: narrow the search.", style = MaterialTheme.typography.bodySmall)
    }
}

private const val MAX_SHOWN = 100

@Composable
internal fun SnippetCard(block: CodeBlock, title: String, onCopy: () -> Unit, onOpen: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(block.language.ifEmpty { "text" }, title, block.heading).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
                )
                IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy") }
            }
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    block.code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    }
}

/** The vault's runbooks (notes tagged #runbook or in a Runbooks folder). */
@Composable
fun RunbooksScreen(onBack: () -> Unit, onPlay: (String) -> Unit, viewModel: SnippetsViewModel = hiltViewModel()) {
    val runbooks by viewModel.runbooks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadRunbooks() }
    FormScaffold(title = "Runbooks", onBack = onBack) {
        val list = runbooks
        when {
            list == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            list.isEmpty() -> EmptyHint("No runbook yet. Tag a note #runbook (or put it in a Runbooks folder): its ## sections are the steps.")
            else -> list.forEach { note ->
                SectionCard(
                    title = note.title,
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    subtitle = note.path,
                    modifier = Modifier.clickable { onPlay(note.path) }
                )
            }
        }
    }
}

/**
 * Plays a runbook step by step; finishing writes an incident log to the inbox. Half-folded on a
 * desk (tabletop, E6), the step stands above the hinge and the buttons lie below it.
 */
@Composable
fun RunbookPlayerScreen(path: String, onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: SnippetsViewModel = hiltViewModel()) {
    LaunchedEffect(path) { viewModel.loadRunbook(path) }
    val loaded by viewModel.runbook.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    val startedAt = remember { LocalDateTime.now().withSecond(0).withNano(0) }
    val results = remember { mutableStateListOf<StepResult>() }
    var index by remember { mutableIntStateOf(0) }
    var stepNote by remember { mutableStateOf("") }
    var runNotes by remember { mutableStateOf("") }
    val copy = rememberCopyToClipboard()
    val tabletop = cloud.wafflecommons.pixelbrainreader.ui.utils.rememberTabletop()

    val (note, steps) = loaded ?: (null to emptyList())

    fun record(status: StepStatus) {
        val step = steps.getOrNull(index) ?: return
        results += StepResult(step, status, LocalDateTime.now().withSecond(0).withNano(0), stepNote.ifBlank { null })
        stepNote = ""
        index++
    }

    val stepContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { if (steps.isEmpty()) 0f else index.toFloat() / steps.size }, modifier = Modifier.fillMaxWidth())
            val step = steps.getOrNull(index)
            if (step != null) {
                Text("Step ${index + 1} of ${steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val prose = step.body.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n").trim()
                if (step.code.isEmpty() && prose.isNotEmpty()) Text(prose, style = MaterialTheme.typography.bodyMedium)
                step.code.forEach { block -> SnippetCard(block, note?.title.orEmpty(), onCopy = { copy(block.code) }, onOpen = null) }
            } else {
                Text("All steps played.", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (index < steps.size) {
                OutlinedTextField(stepNote, { stepNote = it }, label = { Text("Note for this step") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { record(StepStatus.DONE) }) { Text("Done") }
                    OutlinedButton(onClick = { record(StepStatus.FAILED) }) { Text("Failed") }
                    TextButton(onClick = { record(StepStatus.SKIPPED) }) { Text("Skip") }
                }
            }
            OutlinedTextField(runNotes, { runNotes = it }, label = { Text("Incident notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = results.isNotEmpty() && note != null,
                onClick = { note?.let { viewModel.finish(it, startedAt, results.toList(), runNotes, onOpenNote) } }
            ) { Text(if (index < steps.size) "Stop and write the incident log" else "Write the incident log") }
        }
    }

    if (tabletop && note != null && steps.isNotEmpty()) {
        androidx.compose.material3.Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }) { padding ->
            cloud.wafflecommons.pixelbrainreader.ui.utils.HingeSplit(
                modifier = Modifier.padding(padding).fillMaxSize(),
                top = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { stepContent() } },
                bottom = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { controls() } }
            )
        }
        return
    }

    FormScaffold(title = note?.title ?: "Runbook", onBack = onBack, snackbarHostState = snackbarHostState) {
        if (note == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@FormScaffold
        }
        if (steps.isEmpty()) {
            EmptyHint("This runbook has no step: give it ## sections, or a list.")
            return@FormScaffold
        }
        stepContent()
        controls()
    }
}
