@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.ai.GoldenSet
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate

/**
 * Settings › AI & search › Knowledge vault (13.4, S-b and S-j): the vault's indexing, hybrid search,
 * the golden set's measure, and related notes while writing (the editor's bulb keeps its own switch).
 * The AI is off on a read-only device: nothing is embedded there, so the AI's controls are off.
 */
@Composable
fun KnowledgeVaultScreen(onBack: () -> Unit, viewModel: KnowledgeVaultViewModel = hiltViewModel()) {
    val indexing = viewModel.indexingState.collectAsStateWithLifecycle().value
    val hybrid = viewModel.hybridSearch.collectAsStateWithLifecycle().value
    val goldenSummary = viewModel.goldenSummary.collectAsStateWithLifecycle().value
    val goldenRunning = viewModel.isGoldenRunning.collectAsStateWithLifecycle().value
    val related = viewModel.relatedWhileWriting.collectAsStateWithLifecycle().value
    val readOnly = VaultWriteGate.readOnly

    SettingsPageScaffold(page = SettingsPage.KNOWLEDGE, onBack = onBack) {
        SettingsGroup {
            row {
                SettingsNote(
                    if (readOnly) {
                        "The AI is off on this read-only device: the vault is not embedded. Search and the link check still work."
                    } else {
                        "Notes are embedded on this phone in the background each time the app starts, only the passages that changed. Tap below to start now."
                    }
                )
            }
            row {
                VaultIndexRow(
                    state = indexing,
                    enabled = !readOnly,
                    onIndex = viewModel::index,
                    onDismiss = viewModel::dismissIndexingState
                )
            }
            // R2 (12.20): words re-rank what meaning found, never add a note on their own.
            row {
                SettingsSwitchRow(
                    title = "Hybrid search",
                    checked = hybrid,
                    onCheckedChange = viewModel::setHybridSearch,
                    description = if (hybrid) {
                        "On: Chat ranks the notes it finds by meaning higher when they share words with your question. Words never bring in a note on their own."
                    } else {
                        "Off: Chat ranks notes by meaning only."
                    },
                    icon = Icons.Rounded.TravelExplore,
                    enabled = !readOnly,
                    anchor = SettingsAnchors.HYBRID
                )
            }
            // S-j: the editor's bulb, also in Settings.
            row {
                SettingsSwitchRow(
                    title = "Related notes while writing",
                    checked = related,
                    onCheckedChange = viewModel::setRelatedWhileWriting,
                    description = if (related) {
                        "On: while you write, the notes closest to your paragraph show above the text or beside it. The editor's bulb switches it too."
                    } else {
                        "Off: the editor looks for nothing while you write. The editor's bulb switches it back on."
                    },
                    icon = Icons.Rounded.Lightbulb,
                    enabled = !readOnly,
                    anchor = SettingsAnchors.RELATED
                )
            }
            // R1: the retrieval harness, on the vault's golden set (read-only, on the device).
            if (!readOnly) {
                row { MeasureRetrievalRow(summary = goldenSummary, running = goldenRunning, onRun = viewModel::runGoldenSet) }
            }
        }
    }
}

/** Index the vault: what the last or current run is doing, the button, then a finished run's result. */
@Composable
private fun VaultIndexRow(state: IndexingState, enabled: Boolean, onIndex: () -> Unit, onDismiss: () -> Unit) {
    val running = state is IndexingState.Running || state is IndexingState.Enqueued
    SettingsRow(
        title = "Index the vault",
        description = when (state) {
            IndexingState.Enqueued -> "Queued…"
            // Notes left, not "done / total": each run of a long index counts its own total, while
            // what is left only goes down across them.
            is IndexingState.Running -> {
                val left = state.notesTotal - state.notesDone
                if (left > 0) "Indexing… $left ${if (left == 1) "note" else "notes"} to go" else "Indexing…"
            }
            else -> "Embeds the passages that changed since the last run, on this phone"
        },
        icon = Icons.Rounded.Memory,
        enabled = enabled,
        anchor = SettingsAnchors.INDEX,
        below = {
            if (running) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(onClick = onIndex, shapes = ButtonDefaults.shapes(), enabled = enabled && !running) {
                Icon(Icons.Rounded.Memory, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Index now")
            }
            // A finished run's result: dismissed, the row goes back to idle.
            when (state) {
                IndexingState.Succeeded -> AssistChip(
                    onClick = onDismiss,
                    label = { Text("Last run: succeeded ✓") },
                    leadingIcon = {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
                is IndexingState.Failed -> AssistChip(
                    onClick = onDismiss,
                    label = { Text("Last run failed: ${state.reason}") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error)
                )
                else -> Unit
            }
        }
    )
}

/** Measure retrieval: runs the golden set and shows its one-line result. */
@Composable
private fun MeasureRetrievalRow(summary: String?, running: Boolean, onRun: () -> Unit) {
    SettingsRow(
        title = "Measure retrieval",
        description = "Runs the golden set (${GoldenSet.PATH}): how often meaning, words or both find the right notes",
        icon = Icons.Rounded.Science,
        anchor = SettingsAnchors.MEASURE,
        below = {
            OutlinedButton(onClick = onRun, shapes = ButtonDefaults.shapes(), enabled = !running) {
                Text(if (running) "Measuring…" else "Run")
            }
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    )
}
