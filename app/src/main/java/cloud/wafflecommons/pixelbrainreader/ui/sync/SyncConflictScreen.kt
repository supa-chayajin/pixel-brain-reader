package cloud.wafflecommons.pixelbrainreader.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChangedException
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChoice
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictFile
import cloud.wafflecommons.pixelbrainreader.data.sync.JsonConflictSummary
import cloud.wafflecommons.pixelbrainreader.data.sync.PendingConflict
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.utils.isMediumOrWider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The conflict screen's state: whether a conflict waits, its files, then the outcome (pushed now, or
 * by the sync that follows). A conflict can wait with no file left in conflict (GitHub's side moved
 * since): resolving it then just resumes syncing.
 */
data class ConflictUi(
    val waiting: Boolean = true,
    val files: List<ConflictFile> = emptyList(),
    val loading: Boolean = true,
    val resolving: Boolean = false,
    val error: String? = null,
    val pushed: Boolean? = null
)

/**
 * The sync conflict screen (12.15, S1, owner 2026-11-11): a choice per file, then one resolution —
 * one commit on top of GitHub's — and a sync, so the app re-reads what GitHub's side changed.
 * GitHub moving again reloads the files with what is new to choose.
 */
@HiltViewModel
class SyncConflictViewModel @Inject constructor(
    private val jGitProvider: JGitProvider,
    private val syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    private val _ui = MutableStateFlow(ConflictUi())
    val ui: StateFlow<ConflictUi> = _ui.asStateFlow()

    /** The owner's choices, as snapshot state so a chip reflects a tap at once. */
    val choices = mutableStateMapOf<String, ConflictChoice>()

    /** The file whose text is being edited, and that text: here, so a rotation or a fold keeps it. */
    var editingPath by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")

    init {
        load()
        // A pull that stops again (the sync after a resolution, say) shows its files here.
        viewModelScope.launch { jGitProvider.conflictStops.collect { load() } }
    }

    fun load(message: String? = null) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            if (jGitProvider.pendingConflict() == null) {
                choices.clear()
                _ui.value = ConflictUi(waiting = false, loading = false)
                return@launch
            }
            jGitProvider.conflictFiles().fold(
                onSuccess = { files ->
                    choices.keys.retainAll(files.map { it.path }.toSet())
                    _ui.value = ConflictUi(files = files, loading = false, error = message)
                },
                onFailure = { e -> _ui.value = ConflictUi(loading = false, error = e.message ?: "Couldn't read the conflict.") }
            )
        }
    }

    fun choose(path: String, choice: ConflictChoice) {
        choices[path] = choice
    }

    /** Opens the editor on the text chosen before, else git's merge with its markers, else this phone's. */
    fun startEdit(file: ConflictFile) {
        draft = (choices[file.path] as? ConflictChoice.Edited)?.text ?: file.merged ?: file.mine.orEmpty()
        editingPath = file.path
    }

    fun saveEdit() {
        editingPath?.let { choices[it] = ConflictChoice.Edited(draft) }
        editingPath = null
    }

    fun cancelEdit() {
        editingPath = null
    }

    fun resolve() {
        val chosen = choices.toMap()
        viewModelScope.launch {
            _ui.update { it.copy(resolving = true, error = null) }
            jGitProvider.resolveConflicts(chosen).fold(
                onSuccess = { pushed ->
                    _ui.update { it.copy(resolving = false, pushed = pushed) }
                    syncOrchestrator.requestSync(force = true)
                },
                onFailure = { e ->
                    if (e is ConflictChangedException) {
                        load("GitHub changed again: choose for the files that conflict now.")
                    } else {
                        // A hold carries the guard's own message, which says where to review it.
                        _ui.update { it.copy(resolving = false, error = e.message ?: "The resolution didn't apply.") }
                    }
                }
            )
        }
    }
}

@Composable
fun SyncConflictScreen(onBack: () -> Unit, viewModel: SyncConflictViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    FormScaffold(title = "Sync conflict", onBack = onBack) {
        if (ui.loading || ui.resolving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        val pushed = ui.pushed
        if (pushed != null) {
            Text(
                if (pushed) "Resolved and pushed to GitHub. A sync is bringing the app up to date."
                else "Resolved on this phone. GitHub changed again meanwhile: the sync starting now sends it.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onBack) { Text("Done") }
            return@FormScaffold
        }
        if (ui.loading && ui.files.isEmpty()) return@FormScaffold
        if (!ui.waiting) {
            Text("No conflict is waiting.", style = MaterialTheme.typography.bodyLarge)
            return@FormScaffold
        }
        if (ui.files.isEmpty()) {
            if (ui.error != null) {
                Text(ui.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.load() }) { Text("Try again") }
            } else {
                Text(
                    "GitHub's side changed since the sync stopped, and nothing conflicts any more.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = viewModel::resolve, enabled = !ui.resolving, modifier = Modifier.fillMaxWidth()) { Text("Resume syncing") }
            }
            return@FormScaffold
        }
        Text(
            "These files changed both on this phone and on GitHub. Choose what each becomes: the result is one commit on top of GitHub's, and syncing resumes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ui.files.forEach { file ->
            ConflictFileCard(
                file = file,
                choice = viewModel.choices[file.path],
                onChoose = { viewModel.choose(file.path, it) },
                onEdit = { viewModel.startEdit(file) }
            )
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = viewModel::resolve,
            enabled = ui.files.all { it.path in viewModel.choices } && !ui.resolving && !ui.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Resolve and push") }
    }
    viewModel.editingPath?.let { path ->
        EditConflictDialog(
            name = path.substringAfterLast('/'),
            text = viewModel.draft,
            onTextChange = { viewModel.draft = it },
            onSave = viewModel::saveEdit,
            onDismiss = viewModel::cancelEdit
        )
    }
}

private const val DIFF_ROWS = 40

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictFileCard(file: ConflictFile, choice: ConflictChoice?, onChoose: (ConflictChoice) -> Unit, onEdit: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (file.isData) DataSummary(file) else ConflictDiff(theirs = file.theirs, mine = file.mine)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice == ConflictChoice.Mine, onClick = { onChoose(ConflictChoice.Mine) }, label = { Text("Keep mine") })
                FilterChip(selected = choice == ConflictChoice.Theirs, onClick = { onChoose(ConflictChoice.Theirs) }, label = { Text("Keep GitHub's") })
                if (!file.isData) {
                    FilterChip(selected = choice is ConflictChoice.Edited, onClick = onEdit, label = { Text("Edit…") })
                    FilterChip(selected = choice == ConflictChoice.Both, onClick = { onChoose(ConflictChoice.Both) }, label = { Text("Keep both") })
                }
            }
            if (choice == ConflictChoice.Both) {
                Text(
                    "GitHub's version stays; yours is saved beside it as a copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A data file in one line: what differs, never raw JSON (a year of habit logs can be large: parsed off the main thread). */
@Composable
private fun DataSummary(file: ConflictFile) {
    val summary by produceState<String?>(null, file) {
        value = withContext(Dispatchers.Default) { JsonConflictSummary.of(file.mine, file.theirs) }
    }
    Text(summary ?: "Comparing…", style = MaterialTheme.typography.bodyMedium)
}

private val AddedBackground = Color(0xFF1B5E20).copy(alpha = 0.2f)
private val RemovedBackground = Color(0xFFB71C1C).copy(alpha = 0.2f)

/**
 * GitHub's version against this phone's, unchanged runs folded — the Time Machine's diff engine,
 * off the main thread. Side by side from a medium window (an unfolded Fold, a tablet); on a phone
 * width, two columns would wrap every line, so GitHub's lines are marked − and the phone's +.
 */
@Composable
private fun ConflictDiff(theirs: String?, mine: String?) {
    val rows by produceState<List<LineDiff.Row>?>(null, theirs, mine) {
        value = withContext(Dispatchers.Default) {
            LineDiff.fold(LineDiff.compute(old = theirs.orEmpty(), new = mine.orEmpty()), context = 2)
        }
    }
    val sideBySide = isMediumOrWider()
    when {
        mine == null -> DiffLabel("Deleted on this phone.")
        theirs == null -> DiffLabel("Deleted on GitHub.")
        !sideBySide -> DiffLabel("− GitHub · + this phone")
    }
    val shown = rows ?: return
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            val total = if (sideBySide) {
                val sides = LineDiff.sideBySide(shown)
                Row(Modifier.fillMaxWidth()) {
                    DiffLabel("GitHub", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DiffLabel("This phone", Modifier.weight(1f))
                }
                sides.take(DIFF_ROWS).forEach { SideBySideRow(it) }
                sides.size
            } else {
                shown.take(DIFF_ROWS).forEach { UnifiedRow(it) }
                shown.size
            }
            if (total > DIFF_ROWS) DiffLabel("and ${total - DIFF_ROWS} more lines")
        }
    }
}

@Composable
private fun DiffLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun gapText(count: Int) = "⋯ $count unchanged line${if (count == 1) "" else "s"}"

@Composable
private fun UnifiedRow(row: LineDiff.Row) {
    when (row) {
        is LineDiff.Row.Gap -> DiffLabel(gapText(row.count))
        is LineDiff.Row.Line -> when (row.kind) {
            LineDiff.Kind.ADDED -> DiffLine("+ " + row.text, AddedBackground, Modifier.fillMaxWidth())
            LineDiff.Kind.REMOVED -> DiffLine("− " + row.text, RemovedBackground, Modifier.fillMaxWidth())
            LineDiff.Kind.UNCHANGED -> DiffLine("  " + row.text, Color.Transparent, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SideBySideRow(row: LineDiff.SideRow) {
    when (row) {
        is LineDiff.SideRow.Gap -> DiffLabel(gapText(row.count))
        is LineDiff.SideRow.Same -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            DiffLine(row.text, Color.Transparent, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            DiffLine(row.text, Color.Transparent, Modifier.weight(1f).fillMaxHeight())
        }
        is LineDiff.SideRow.Changed -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            DiffLine(row.old.orEmpty(), if (row.old != null) RemovedBackground else Color.Transparent, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            DiffLine(row.new.orEmpty(), if (row.new != null) AddedBackground else Color.Transparent, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun DiffLine(text: String, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun EditConflictDialog(name: String, text: String, onTextChange: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Each disagreement shows this phone's lines, then GitHub's, between markers: keep what you want and delete the rest, markers included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Use this text") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The conflict waiting, for Daily's banner and Settings' row: re-read when they show, after each
 * sync, on each stopped pull and on a return to the app. [stops] lets the app open the screen.
 */
@HiltViewModel
class PendingConflictViewModel @Inject constructor(
    private val jGitProvider: JGitProvider,
    syncOrchestrator: SyncOrchestrator,
    appClock: AppClock
) : ViewModel() {

    private val _pending = MutableStateFlow<PendingConflict?>(null)
    val pending: StateFlow<PendingConflict?> = _pending.asStateFlow()

    /** One event per pull that stopped on a conflict or was paused by one. */
    val stops: SharedFlow<Unit> get() = jGitProvider.conflictStops

    init {
        viewModelScope.launch { syncOrchestrator.syncState.collect { reload() } }
        viewModelScope.launch { appClock.foregroundEvents.collect { reload() } }
        viewModelScope.launch { jGitProvider.conflictStops.collect { reload() } }
    }

    fun reload() {
        // A read-only device never resolves (its pull is fast-forward only, so it never stops on one).
        if (VaultWriteGate.readOnly) return
        viewModelScope.launch { _pending.value = jGitProvider.pendingConflict() }
    }
}

@Composable
fun SyncConflictBanner(pending: PendingConflict, onResolve: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SyncProblem, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (pending.paths.size == 1) "Sync stopped: 1 file changed on both sides"
                else "Sync stopped: ${pending.paths.size} files changed on both sides",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onResolve) { Text("Resolve") }
        }
    }
}
