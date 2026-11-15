package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FilingSuggestion
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultRulesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.work.InboxSnooze
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A message for the inbox's snackbar, with Undo when the step can be taken back. */
data class InboxMessage(val text: String, val undo: (() -> Unit)? = null)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: VaultRulesRepository,
    private val userPreferences: UserPreferencesRepository,
    private val appClock: AppClock,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) : ViewModel() {

    private val _notes = MutableStateFlow<List<WorkNote>?>(null)

    /** The inbox without the notes snoozed for later (E7). */
    val notes: StateFlow<List<WorkNote>?> = combine(_notes, userPreferences.inboxSnoozed, appClock.today) { notes, snoozed, today ->
        val away = InboxSnooze.active(snoozed, today).keys
        notes?.filterNot { it.path in away }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many notes wait for a later day. */
    val snoozedCount: StateFlow<Int> = combine(_notes, userPreferences.inboxSnoozed, appClock.today) { notes, snoozed, today ->
        val away = InboxSnooze.active(snoozed, today).keys
        notes.orEmpty().count { it.path in away }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Suggestions per note path, computed on demand (each one runs the on-device search). */
    val suggestions = mutableStateMapOf<String, FilingSuggestion>()

    private val _messages = Channel<InboxMessage>(Channel.BUFFERED)
    val messages: Flow<InboxMessage> = _messages.receiveAsFlow()

    fun load() {
        viewModelScope.launch { _notes.value = repository.inboxNotes() }
    }

    fun suggest(note: WorkNote) {
        viewModelScope.launch { suggestions[note.path] = suggestionFor(note) }
    }

    fun file(note: WorkNote, folder: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                val path = repository.file(note, folder, tags)
                _messages.send(InboxMessage("Filed in ${path.substringBeforeLast('/')}"))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(InboxMessage("Couldn't file the note: ${e.message}"))
            }
        }
    }

    /** A swipe to the right (E7): files the note in its first suggested folder, or shows the choices when none stands out. */
    fun fileSuggested(note: WorkNote) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            val suggestion = suggestions[note.path] ?: suggestionFor(note).also { suggestions[note.path] = it }
            val folder = suggestion.folders.firstOrNull()
            if (folder == null) {
                _messages.send(InboxMessage("No folder stands out: open the note and move it by hand."))
            } else {
                file(note, folder, emptyList())
            }
        }
    }

    /** A swipe to the left (E7): the note leaves the list for a week, untouched. */
    fun snooze(note: WorkNote) {
        viewModelScope.launch {
            val today = appClock.today.value
            userPreferences.updateInboxSnoozed { InboxSnooze.snooze(it, note.path, today) }
            _messages.send(InboxMessage("Back in a week", undo = { wake(note) }))
        }
    }

    private fun wake(note: WorkNote) {
        viewModelScope.launch { userPreferences.updateInboxSnoozed { InboxSnooze.wake(it, note.path, appClock.today.value) } }
    }

    /** Deletes the note (E7); Undo writes it back as it was read just before. */
    fun delete(note: WorkNote) {
        if (VaultWriteGate.refuses()) return
        viewModelScope.launch {
            val content = fileRepository.readFile(note.path) ?: note.content
            if (fileRepository.deleteFile(note.path).isSuccess) {
                pushScheduler.requestPush()
                load()
                _messages.send(InboxMessage("Deleted", undo = { restore(note.path, content) }))
            } else {
                _messages.send(InboxMessage("Couldn't delete the note"))
            }
        }
    }

    private fun restore(path: String, content: String) {
        viewModelScope.launch {
            fileRepository.saveFileLocally(path, content)
            pushScheduler.requestPush()
            load()
        }
    }

    /** Shows the snoozed notes again. */
    fun wakeAll() {
        viewModelScope.launch { userPreferences.updateInboxSnoozed { emptySet() } }
    }

    private suspend fun suggestionFor(note: WorkNote): FilingSuggestion = try {
        repository.suggest(note)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FilingSuggestion(emptyList(), emptyList())
    }
}

/**
 * Inbox filing (roadmap 2.6) and its swipe triage (E7, 12.21): each inbox note with folder and tag
 * suggestions, filed in one tap. Swipe right to file in the suggested folder, left to see it again
 * in a week.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: InboxViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val snoozed by viewModel.snoozedCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = if (message.undo != null) "Undo" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }

    FormScaffold(title = "Inbox", onBack = onBack, snackbarHostState = snackbarHostState) {
        // Filing moves the note: a read-only device lists and opens the inbox only (12.15, S2).
        val readOnly = VaultWriteGate.readOnly
        if (readOnly) ReadOnlyNotice()
        val list = notes
        when {
            list == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            list.isEmpty() -> EmptyHint(if (snoozed > 0) "Nothing left for today." else "The inbox is empty.")
            else -> {
                if (!readOnly) {
                    Text(
                        "Swipe right to file a note in its suggested folder, left to see it again in a week.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                list.forEach { note ->
                    key(note.path) {
                        TriageSwipe(
                            enabled = !readOnly,
                            onFile = { viewModel.fileSuggested(note) },
                            onSnooze = { viewModel.snooze(note) }
                        ) {
                            InboxCard(note, viewModel, readOnly, onOpenNote)
                        }
                    }
                }
            }
        }
        if (snoozed > 0) {
            TextButton(onClick = viewModel::wakeAll) { Text("$snoozed snoozed · show them now") }
        }
    }
}

/** The swipe (E7): right files, left snoozes; the card springs back while the list catches up. */
@Composable
private fun TriageSwipe(enabled: Boolean, onFile: () -> Unit, onSnooze: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val currentOnFile by rememberUpdatedState(onFile)
    val currentOnSnooze by rememberUpdatedState(onSnooze)
    // One stable callback: SwipeToDismissBox restarts its effect when the lambda changes, which
    // would file the same note twice.
    val onDismiss = remember(state) {
        { direction: SwipeToDismissBoxValue ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnFile()
                SwipeToDismissBoxValue.EndToStart -> currentOnSnooze()
                else -> Unit
            }
            scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }
            Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = onDismiss,
        backgroundContent = {
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
                label = "triageBackground"
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("File", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    SwipeToDismissBoxValue.EndToStart -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("In a week", color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Icon(Icons.Rounded.Snooze, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    else -> Unit
                }
            }
        }
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxCard(note: WorkNote, viewModel: InboxViewModel, readOnly: Boolean, onOpenNote: (String) -> Unit) {
    val suggestion = viewModel.suggestions[note.path]
    var folder by remember(note.path) { mutableStateOf<String?>(null) }
    val tags = remember(note.path) { mutableStateListOf<String>() }
    SectionCard(
        title = note.title,
        icon = Icons.Rounded.MoveToInbox,
        subtitle = listOfNotNull(note.created?.toString(), note.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString(" · "),
        trailing = { TextButton(onClick = { onOpenNote(note.path) }) { Text("Open") } }
    ) {
        if (readOnly) {
            // Nothing to file from here.
        } else if (suggestion == null) {
            OutlinedButton(onClick = { viewModel.suggest(note) }) { Text("Suggest a folder") }
        } else {
            if (suggestion.folders.isEmpty()) {
                Text("No folder stands out: open the note and move it by hand.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Folder", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestion.folders.forEach { f -> FilterChip(selected = folder == f, onClick = { folder = f }, label = { Text(f) }) }
                }
            }
            if (suggestion.tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestion.tags.forEach { t ->
                        FilterChip(selected = t in tags, onClick = { if (t in tags) tags.remove(t) else tags.add(t) }, label = { Text("#$t") })
                    }
                }
            }
            Row {
                Button(enabled = folder != null, onClick = { folder?.let { viewModel.file(note, it, tags.toList()) } }) { Text("File") }
            }
        }
        if (!readOnly) {
            TextButton(onClick = { viewModel.delete(note) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
