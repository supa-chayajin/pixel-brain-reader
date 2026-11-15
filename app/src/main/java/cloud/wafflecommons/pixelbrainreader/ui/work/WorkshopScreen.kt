package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.work.Workshop
import cloud.wafflecommons.pixelbrainreader.data.work.WorkshopRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/** The workshop kit (W2, 12.21): one workshop from a meeting note, kept on the phone until its synthesis. */
@HiltViewModel
class WorkshopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WorkshopRepository,
    private val appClock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    val notePath: String = savedStateHandle.get<String>("path").orEmpty()

    /** The workshop under way, this note's or another's. */
    val draft: StateFlow<Workshop.Draft?> = repository.draft.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This note's workshop as it would start: the agenda and its minutes. Null while reading or without an agenda. */
    private val _prepared = MutableStateFlow<Workshop.Draft?>(null)
    val prepared: StateFlow<Workshop.Draft?> = _prepared.asStateFlow()
    private val _noAgenda = MutableStateFlow(false)
    val noAgenda: StateFlow<Boolean> = _noAgenda.asStateFlow()
    private val _writing = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = _writing.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()
    private val _written = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The synthesis note's path, once written. */
    val written: SharedFlow<String> = _written.asSharedFlow()

    init {
        viewModelScope.launch {
            val prepared = repository.prepare(notePath)
            _prepared.value = prepared
            _noAgenda.value = prepared == null
        }
    }

    /** Shares [minutes] evenly over the agenda again, before the start. */
    fun setTotal(minutes: Int) {
        _prepared.update { prepared -> prepared?.copy(steps = Workshop.steps(prepared.steps.map { it.title }, minutes)) }
    }

    fun start() {
        val prepared = _prepared.value ?: return
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            repository.update { current -> current ?: Workshop.start(prepared, System.currentTimeMillis()) }
        }
    }

    fun next() = change { Workshop.next(it, System.currentTimeMillis()) }

    fun add(column: Workshop.Column, text: String) = change { Workshop.add(it, UUID.randomUUID().toString(), column, text) }

    fun remove(id: String) = change { Workshop.remove(it, id) }

    fun discard() {
        viewModelScope.launch { repository.update { null } }
    }

    /** Writes the synthesis, in the app's scope: leaving the screen while Nano writes doesn't lose it. */
    fun finish(withSummary: Boolean) {
        val draft = draft.value?.takeIf { it.notePath == notePath } ?: return
        if (VaultWriteGate.refuses() || _writing.value) return
        _writing.value = true
        _failed.value = false
        appScope.launch {
            try {
                _written.emit(repository.finish(draft, appClock.today.value, withSummary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.value = true
            } finally {
                _writing.value = false
            }
        }
    }

    private fun change(transform: (Workshop.Draft) -> Workshop.Draft) {
        viewModelScope.launch {
            repository.update { current -> current?.takeIf { it.notePath == notePath }?.let(transform) ?: current }
        }
    }
}

/**
 * Run a meeting note as a workshop (W2, the owner's D4): its agenda as timed steps, a board of
 * ideas, decisions, actions and questions filled along the way, and a French synthesis note beside
 * the meeting at the end, linked from it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkshopScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: WorkshopViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val prepared by viewModel.prepared.collectAsStateWithLifecycle()
    val noAgenda by viewModel.noAgenda.collectAsStateWithLifecycle()
    val writing by viewModel.writing.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    val mine = draft?.takeIf { it.notePath == viewModel.notePath }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.written.collect { path -> onOpenNote(path) } }

    // A workshop under way keeps the screen on.
    val running = mine != null && !mine.over
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    FormScaffold(
        title = "Workshop",
        onBack = onBack,
        actions = {
            if (mine != null && !readOnly) {
                IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Discard the workshop") }
            }
        }
    ) {
        if (readOnly) ReadOnlyNotice()
        val other = draft?.takeIf { it.notePath != viewModel.notePath }
        when {
            other != null -> SectionCard(title = "Another workshop is under way", icon = Icons.Rounded.Groups, subtitle = other.title) {
                Text("Finish it from its meeting, or discard it to start this one.", style = MaterialTheme.typography.bodyMedium)
                if (!readOnly) TextButton(onClick = { confirmDiscard = true }) { Text("Discard it") }
            }
            mine != null -> RunningWorkshop(
                draft = mine,
                readOnly = readOnly,
                writing = writing,
                failed = failed,
                onNext = viewModel::next,
                onAdd = viewModel::add,
                onRemove = viewModel::remove,
                onFinish = { confirmFinish = true }
            )
            noAgenda -> EmptyHint("This note has no agenda yet: list its points under the agenda heading, then come back.")
            else -> prepared?.let { WorkshopSetup(it, readOnly, onTotal = viewModel::setTotal, onStart = viewModel::start) }
                ?: EmptyHint("Reading the meeting note…")
        }
    }

    if (confirmFinish) {
        var withSummary by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Write the synthesis?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A note beside the meeting, linked from it: the board by column, then how the agenda ran. The workshop then ends.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withSummary, onCheckedChange = { withSummary = it })
                        Text("Begin with Gemini Nano's summary (on the phone)")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmFinish = false; viewModel.finish(withSummary) }) { Text("Write it") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Not yet") } }
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard the workshop?") },
            text = { Text("Its board leaves the phone; nothing was written to the vault.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkshopSetup(prepared: Workshop.Draft, readOnly: Boolean, onTotal: (Int) -> Unit, onStart: () -> Unit) {
    val total = prepared.steps.sumOf { it.minutes }
    SectionCard(title = prepared.title, icon = Icons.Rounded.Groups, subtitle = "${prepared.steps.size} points · $total min") {
        prepared.steps.forEachIndexed { index, step ->
            Text("${index + 1}. ${step.title} — ${step.minutes} min", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("How long", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 45, 60, 90, 120).forEach { minutes ->
            FilterChip(selected = total == minutes, onClick = { onTotal(minutes) }, label = { Text("$minutes min") }, enabled = !readOnly)
        }
    }
    Button(onClick = onStart, enabled = !readOnly, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Timer, contentDescription = null)
        Text("Start the workshop", modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningWorkshop(
    draft: Workshop.Draft,
    readOnly: Boolean,
    writing: Boolean,
    failed: Boolean,
    onNext: () -> Unit,
    onAdd: (Workshop.Column, String) -> Unit,
    onRemove: (String) -> Unit,
    onFinish: () -> Unit
) {
    // The countdown ticks each second while a step runs.
    val now by produceState(System.currentTimeMillis(), draft.current, draft.over) {
        while (!draft.over) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val step = draft.steps.getOrNull(draft.current)
    if (step != null) {
        val left = Workshop.secondsLeft(step, now)
        SectionCard(
            title = step.title,
            icon = Icons.Rounded.Timer,
            tint = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            subtitle = "Point ${draft.current + 1} of ${draft.steps.size} · ${step.minutes} min planned"
        ) {
            Text(
                text = (if (left < 0) "+" else "") + clock(abs(left)) + if (left < 0) " over" else " left",
                style = MaterialTheme.typography.displaySmall,
                color = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = { (1f - left.toFloat() / (step.minutes * 60f)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            draft.steps.getOrNull(draft.current + 1)?.let { upcoming ->
                Text("Next: ${upcoming.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onNext, enabled = !readOnly) {
                Text(if (draft.current + 1 < draft.steps.size) "Next point" else "End the last point")
            }
        }
    } else {
        SectionCard(title = "Every point is done", icon = Icons.Rounded.Timer, subtitle = "${draft.steps.mapNotNull(Workshop::minutesTaken).sum()} min · planned ${draft.steps.sumOf { it.minutes }}")
    }

    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Capture") },
        placeholder = { Text("An idea, a decision, an action, a question…") },
        enabled = !readOnly,
        modifier = Modifier.fillMaxWidth()
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Workshop.Column.entries.forEach { column ->
            FilledTonalButton(
                onClick = { onAdd(column, text); text = "" },
                enabled = !readOnly && text.isNotBlank()
            ) { Text("+ ${singular(column)}") }
        }
    }

    Workshop.Column.entries.forEach { column ->
        val cards = Workshop.cardsIn(draft, column)
        SectionCard(title = column.label, subtitle = if (cards.isEmpty()) "None yet" else "${cards.size}") {
            cards.forEach { card ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• ${card.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!readOnly) {
                        IconButton(onClick = { onRemove(card.id) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
                    }
                }
            }
        }
    }

    if (failed) Text("The synthesis couldn't be written. The board is still here.", color = MaterialTheme.colorScheme.error)
    Button(onClick = onFinish, enabled = !readOnly && !writing, modifier = Modifier.fillMaxWidth()) {
        Text(if (writing) "Writing the synthesis…" else "Write the synthesis")
    }
}

private fun singular(column: Workshop.Column): String = when (column) {
    Workshop.Column.IDEA -> "Idea"
    Workshop.Column.DECISION -> "Decision"
    Workshop.Column.ACTION -> "Action"
    Workshop.Column.QUESTION -> "Question"
}

private fun clock(seconds: Long): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
