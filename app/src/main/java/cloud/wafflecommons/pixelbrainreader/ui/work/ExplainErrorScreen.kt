package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.work.BugNote
import cloud.wafflecommons.pixelbrainreader.data.work.ErrorExplainRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ErrorLookupResult
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a lookup stands. */
sealed interface ErrorPhase {
    data object Idle : ErrorPhase
    data object Looking : ErrorPhase
    data object NotAnError : ErrorPhase
    data class Found(val result: ErrorLookupResult) : ErrorPhase
}

/** The explanation's state: [generalKnowledge] when no note matched and the owner asked anyway. */
sealed interface Explanation {
    data object None : Explanation
    data object Running : Explanation
    data class Ready(val text: String, val generalKnowledge: Boolean) : Explanation
    data class Failed(val reason: String) : Explanation
}

@HiltViewModel
class ExplainErrorViewModel @Inject constructor(
    private val repository: ErrorExplainRepository,
    localAiManager: LocalAiManager
) : ViewModel() {

    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    // Synchronous text state: a text field fed through a flow drops characters.
    var input by mutableStateOf("")
    var phase by mutableStateOf<ErrorPhase>(ErrorPhase.Idle)
        private set
    var explanation by mutableStateOf<Explanation>(Explanation.None)
        private set
    var isSaving by mutableStateOf(false)
        private set

    init {
        repository.takePending()?.let { shared ->
            input = shared
            lookUp()
        }
    }

    fun lookUp() {
        val text = input.trim()
        if (text.isEmpty() || phase == ErrorPhase.Looking) return
        phase = ErrorPhase.Looking
        explanation = Explanation.None
        viewModelScope.launch {
            val result = repository.lookup(text)
            phase = if (result == null) ErrorPhase.NotAnError else ErrorPhase.Found(result)
            // Notes met it before: their explanation comes by itself when the model is ready.
            if (result != null && result.occurrences.isNotEmpty() && nanoState.value is NanoState.Ready) explain(generalKnowledge = false)
        }
    }

    fun explain(generalKnowledge: Boolean) {
        val result = (phase as? ErrorPhase.Found)?.result ?: return
        if (explanation == Explanation.Running) return
        explanation = Explanation.Running
        viewModelScope.launch {
            explanation = repository.explain(result, generalKnowledge).fold(
                onSuccess = { Explanation.Ready(it.trim(), generalKnowledge) },
                onFailure = { Explanation.Failed(it.message ?: "Gemini Nano could not answer") }
            )
        }
    }

    fun createBugNote(onCreated: (String) -> Unit, onFailed: (String) -> Unit) {
        val result = (phase as? ErrorPhase.Found)?.result ?: return
        if (isSaving || VaultWriteGate.refuses()) return
        isSaving = true
        val ready = explanation as? Explanation.Ready
        viewModelScope.launch {
            try {
                onCreated(repository.writeBugNote(result, ready?.text, ready?.generalKnowledge == true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed(e.message ?: "The bug note could not be written")
            } finally {
                isSaving = false
            }
        }
    }
}

/** C8: paste or share an error; see where the vault met it, an explanation, and a bug note. */
@Composable
fun ExplainErrorScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: ExplainErrorViewModel = hiltViewModel()) {
    val nanoState by viewModel.nanoState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var failure by remember { mutableStateOf<String?>(null) }
    val nanoReady = nanoState is NanoState.Ready

    FormScaffold(title = "Explain an error", onBack = onBack, snackbarHostState = snackbarHostState) {
        SectionCard(title = "The error", icon = Icons.Rounded.BugReport, subtitle = "A stack trace or an error line. Nothing leaves the phone.") {
            OutlinedTextField(
                value = viewModel.input,
                onValueChange = { viewModel.input = it },
                minLines = 4,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::lookUp,
                enabled = viewModel.input.isNotBlank() && viewModel.phase != ErrorPhase.Looking,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Look it up") }
        }

        when (val phase = viewModel.phase) {
            ErrorPhase.Idle -> Unit
            ErrorPhase.Looking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ErrorPhase.NotAnError -> EmptyHint("This doesn't read as an error. Paste a stack trace, or a line with the error's type or code.")
            is ErrorPhase.Found -> FoundResult(
                result = phase.result,
                explanation = viewModel.explanation,
                nanoReady = nanoReady,
                isSaving = viewModel.isSaving,
                onOpenNote = onOpenNote,
                onExplain = viewModel::explain,
                onCreateNote = { viewModel.createBugNote(onCreated = onOpenNote, onFailed = { failure = it }) }
            )
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FoundResult(
    result: ErrorLookupResult,
    explanation: Explanation,
    nanoReady: Boolean,
    isSaving: Boolean,
    onOpenNote: (String) -> Unit,
    onExplain: (Boolean) -> Unit,
    onCreateNote: () -> Unit
) {
    val trace = result.trace
    SectionCard(title = "Seen before · ${result.occurrences.size}", icon = Icons.Rounded.History, subtitle = "${trace.ecosystem.label} · ${trace.headline}") {
        if (result.occurrences.isEmpty()) {
            Text("Not seen in your notes.", style = MaterialTheme.typography.bodyMedium)
        }
        result.occurrences.forEach { occurrence ->
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpenNote(occurrence.path) }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        occurrence.path.substringAfterLast('/').removeSuffix(".md"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (occurrence.isRunbook) AssistChip(onClick = { onOpenNote(occurrence.path) }, label = { Text("Runbook") })
                }
                if (occurrence.excerpt.isNotBlank()) {
                    Text(
                        occurrence.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    SectionCard(title = "Explanation", icon = Icons.Rounded.AutoAwesome, subtitle = "Gemini Nano, on the phone, in French") {
        when (explanation) {
            Explanation.Running -> {
                Text("Gemini Nano is reading the error…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            is Explanation.Ready -> {
                Text(explanation.text, style = MaterialTheme.typography.bodyMedium)
                if (explanation.generalKnowledge) {
                    Text(
                        "From general knowledge, not from your notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            is Explanation.Failed -> Text("No explanation: ${explanation.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Explanation.None -> when {
                VaultWriteGate.readOnly || !nanoReady -> Text("No explanation: the on-device AI is not ready.", style = MaterialTheme.typography.bodyMedium)
                result.occurrences.isEmpty() -> OutlinedButton(onClick = { onExplain(true) }) { Text("Ask Gemini Nano anyway") }
                else -> OutlinedButton(onClick = { onExplain(false) }) { Text("Explain") }
            }
        }
    }

    if (!VaultWriteGate.readOnly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onCreateNote, enabled = !isSaving && explanation != Explanation.Running) { Text("Create bug note") }
            Spacer(Modifier.width(12.dp))
            Text(
                "In ${BugNote.folder(result.projectRoot)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
