package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.FilingSuggestion
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultRulesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
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
class InboxViewModel @Inject constructor(private val repository: VaultRulesRepository) : ViewModel() {

    private val _notes = MutableStateFlow<List<WorkNote>?>(null)
    val notes: StateFlow<List<WorkNote>?> = _notes.asStateFlow()

    /** Suggestions per note path, computed on demand (each one runs the on-device search). */
    val suggestions = mutableStateMapOf<String, FilingSuggestion>()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun load() {
        viewModelScope.launch { _notes.value = repository.inboxNotes() }
    }

    fun suggest(note: WorkNote) {
        viewModelScope.launch {
            suggestions[note.path] = try {
                repository.suggest(note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FilingSuggestion(emptyList(), emptyList())
            }
        }
    }

    fun file(note: WorkNote, folder: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                val path = repository.file(note, folder, tags)
                _messages.send("Filed in ${path.substringBeforeLast('/')}")
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Couldn't file the note: ${e.message}")
            }
        }
    }
}

/** Inbox filing (roadmap 2.6): each inbox note with folder and tag suggestions, filed in one tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: InboxViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    FormScaffold(title = "Inbox", onBack = onBack, snackbarHostState = snackbarHostState) {
        val list = notes
        when {
            list == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            list.isEmpty() -> EmptyHint("The inbox is empty.")
            else -> list.forEach { note ->
                val suggestion = viewModel.suggestions[note.path]
                var folder by remember(note.path) { mutableStateOf<String?>(null) }
                val tags = remember(note.path) { mutableStateListOf<String>() }
                SectionCard(
                    title = note.title,
                    icon = Icons.Rounded.MoveToInbox,
                    subtitle = listOfNotNull(note.created?.toString(), note.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString(" · "),
                    trailing = { TextButton(onClick = { onOpenNote(note.path) }) { Text("Open") } }
                ) {
                    if (suggestion == null) {
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
                }
            }
        }
    }
}
