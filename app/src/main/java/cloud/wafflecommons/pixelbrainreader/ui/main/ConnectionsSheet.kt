package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.Connections
import cloud.wafflecommons.pixelbrainreader.data.repository.ConnectionsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel @Inject constructor(private val repository: ConnectionsRepository) : ViewModel() {

    private val _connections = MutableStateFlow<Connections?>(null)
    val connections: StateFlow<Connections?> = _connections.asStateFlow()

    fun load(path: String) {
        _connections.value = null
        viewModelScope.launch {
            _connections.value = try {
                repository.connections(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Connections(emptyList(), emptyList(), emptyList())
            }
        }
    }

    fun link(mention: NoteRef, path: String) {
        viewModelScope.launch {
            if (repository.linkMention(mention, path)) load(path)
        }
    }
}

/** A note's connections (E2): backlinks, unlinked mentions (linkable in one tap), related notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSheet(path: String, onOpen: (String) -> Unit, onDismiss: () -> Unit, viewModel: ConnectionsViewModel = hiltViewModel()) {
    LaunchedEffect(path) { viewModel.load(path) }
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Connections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val c = connections
            if (c == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@Column
            }
            Section("Backlinks", c.backlinks, empty = "No note links here yet.", onOpen = onOpen)
            Section(
                "Unlinked mentions",
                c.mentions,
                empty = "No other note names this one without linking it.",
                onOpen = onOpen,
                action = "Link" to { ref -> viewModel.link(ref, path) }
            )
            Section("Related", c.related, empty = "Nothing close enough in meaning (on-device search).", onOpen = onOpen)
        }
    }
}

@Composable
private fun Section(
    title: String,
    refs: List<NoteRef>,
    empty: String,
    onOpen: (String) -> Unit,
    action: Pair<String, (NoteRef) -> Unit>? = null
) {
    Text("$title · ${refs.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    if (refs.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    refs.forEach { ref ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).clickable { onOpen(ref.path) }.padding(vertical = 4.dp)) {
                Text(ref.title, style = MaterialTheme.typography.bodyLarge)
                ref.excerpt?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            action?.let { (label, run) -> TextButton(onClick = { run(ref) }) { Text(label) } }
        }
    }
}
