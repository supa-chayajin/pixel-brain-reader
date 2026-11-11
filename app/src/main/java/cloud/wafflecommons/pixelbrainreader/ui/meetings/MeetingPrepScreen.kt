package cloud.wafflecommons.pixelbrainreader.ui.meetings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrepRepository
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrepResult
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** D4: a calendar entry's prep (route `meeting_prep?entry=`), and its minutes in one tap. */
@HiltViewModel
class MeetingPrepViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MeetingPrepRepository
) : ViewModel() {

    sealed interface PrepState {
        data object Loading : PrepState
        /** The entry is gone: a sync replaced today's calendar rows. */
        data object Gone : PrepState
        data class Ready(val prep: MeetingPrepResult) : PrepState
    }

    private val entryId: String = savedStateHandle.get<String>("entry").orEmpty()

    private val _state = MutableStateFlow<PrepState>(PrepState.Loading)
    val state: StateFlow<PrepState> = _state.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                repository.prep(entryId)?.let { PrepState.Ready(it) } ?: PrepState.Gone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrepState.Gone
            }
        }
    }

    /** Writes the meeting's note with the prep in it, then [open]s it. */
    fun writeMinutes(open: (String) -> Unit) {
        val prep = (_state.value as? PrepState.Ready)?.prep ?: return
        _problem.value = null
        viewModelScope.launch {
            try {
                open(repository.writeMinutes(prep))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = "The meeting note couldn't be written."
            }
        }
    }
}

@Composable
fun MeetingPrepScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: MeetingPrepViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()

    FormScaffold(title = "Meeting prep", onBack = onBack) {
        when (val current = state) {
            MeetingPrepViewModel.PrepState.Loading -> EmptyHint("Reading your notes…")
            MeetingPrepViewModel.PrepState.Gone -> EmptyHint("This meeting is no longer on the timeline.")
            is MeetingPrepViewModel.PrepState.Ready -> {
                val prep = current.prep
                Text(prep.topic.ifEmpty { prep.entry.content }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${prep.entry.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))} · ${prep.entry.time.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (prep.workHidden) {
                    Text(
                        "Work notes and the mission log stay hidden outside ${prep.workLabel} mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                SectionCard(title = "Notes about it · ${prep.notes.size}", icon = Icons.Rounded.Description) {
                    if (prep.notes.isEmpty()) EmptyHint("Nothing in your notes about it yet.")
                    prep.notes.forEach { note ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { onOpenNote(note.path) }.padding(vertical = 6.dp)) {
                            Text(note.path.substringAfterLast('/').removeSuffix(".md"), style = MaterialTheme.typography.bodyLarge)
                            val excerpt = note.excerpt.lines().filterNot { it.startsWith("[Note:") }.joinToString(" ").trim()
                            if (excerpt.isNotBlank()) {
                                Text(
                                    excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (!prep.workHidden) {
                    SectionCard(title = "Last decisions about it", icon = Icons.Rounded.Gavel) {
                        if (prep.decisions.isEmpty()) EmptyHint("No decision in the mission log about it.")
                        prep.decisions.forEach { decision ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(decision.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    decision.at.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (!VaultWriteGate.readOnly) {
                    Button(onClick = { viewModel.writeMinutes(onOpenNote) }) { Text("Write the meeting note") }
                }
            }
        }
    }
}
