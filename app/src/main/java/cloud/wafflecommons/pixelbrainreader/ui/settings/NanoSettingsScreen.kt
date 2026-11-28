@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate

/**
 * Settings › AI & search › Gemini Nano (13.4, S-b): the model's state as rows (download, progress,
 * ready and its storage, unavailable, an error to retry) and the self-test. The 12.x one-option
 * "Local AI" choice is gone: there is one model and nothing to choose.
 */
@Composable
fun NanoSettingsScreen(onBack: () -> Unit, viewModel: NanoSettingsViewModel = hiltViewModel()) {
    val nanoState = viewModel.nanoState.collectAsStateWithLifecycle().value
    val selfTest = viewModel.selfTest.collectAsStateWithLifecycle().value
    val isSelfTestRunning = viewModel.isSelfTestRunning.collectAsStateWithLifecycle().value
    val readOnly = VaultWriteGate.readOnly

    SettingsPageScaffold(page = SettingsPage.NANO, onBack = onBack) {
        SettingsGroup {
            row { SettingsNote("Every answer is written on this phone by Gemini Nano. Nothing leaves it.") }
            if (readOnly) {
                // LocalAiManager is off on a read-only device (settled decision #18).
                row {
                    SettingsNote(
                        "The AI is off on this read-only device.",
                        modifier = Modifier.settingsAnchor(SettingsAnchors.MODEL)
                    )
                }
            } else {
                row { NanoModelRow(state = nanoState, onDownload = viewModel::download) }
                if (nanoState is NanoState.Ready) {
                    row {
                        SettingsRow(
                            title = "Manage storage",
                            description = "In AICore's settings, where the model can be removed from the phone",
                            icon = Icons.Rounded.FolderOpen,
                            onClick = viewModel::openStorage
                        )
                    }
                }
                row {
                    SettingsRow(
                        title = "Self-test",
                        description = "One real request and one streamed answer through the app's own path, timed",
                        icon = Icons.Rounded.Speed,
                        anchor = SettingsAnchors.SELF_TEST,
                        below = {
                            NanoSelfTestRow(
                                result = selfTest,
                                isRunning = isSelfTestRunning,
                                enabled = nanoState is NanoState.Ready,
                                onRun = viewModel::runSelfTest
                            )
                        }
                    )
                }
            }
        }
    }
}

/** The model's row: what it is doing, with the button or the progress that goes with it. */
@Composable
private fun NanoModelRow(state: NanoState, onDownload: () -> Unit) {
    val icon = SettingsPage.NANO.icon
    when (state) {
        NanoState.Unknown, NanoState.Checking -> SettingsRow(
            title = "Model",
            description = "Checking model availability…",
            icon = icon,
            anchor = SettingsAnchors.MODEL,
            trailing = { LoadingIndicator(modifier = Modifier.size(24.dp)) }
        )
        NanoState.NotDownloaded -> SettingsRow(
            title = "Model",
            description = "Stays on-device. Required before the AI can answer.",
            icon = icon,
            anchor = SettingsAnchors.MODEL,
            below = {
                Button(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download Gemini Nano (~1.5 GB)")
                }
            }
        )
        is NanoState.Downloading -> SettingsRow(
            title = "Model",
            description = if (state.totalBytes > 0L) {
                "Downloading model… ${formatModelBytes(state.bytesDownloaded)} / ${formatModelBytes(state.totalBytes)}"
            } else {
                "Downloading model…"
            },
            icon = icon,
            anchor = SettingsAnchors.MODEL,
            below = {
                if (state.progress in 0f..1f) {
                    LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        NanoState.Ready -> SettingsRow(
            title = "Model",
            description = "Model ready for offline use",
            icon = icon,
            anchor = SettingsAnchors.MODEL,
            trailing = {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Ready", tint = MaterialTheme.colorScheme.primary)
            }
        )
        is NanoState.Unavailable -> SettingsRow(
            title = "Model",
            description = "Local AI unavailable on this device: ${state.reason}",
            icon = icon,
            anchor = SettingsAnchors.MODEL
        )
        is NanoState.Error -> SettingsRow(
            title = "Model",
            description = "Download failed: " +
                (state.cause.localizedMessage ?: state.cause.message ?: state.cause::class.java.simpleName),
            icon = icon,
            anchor = SettingsAnchors.MODEL,
            below = {
                OutlinedButton(onClick = onDownload, shapes = ButtonDefaults.shapes()) { Text("Retry download") }
            }
        )
    }
}

private fun formatModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1_000_000.0
    if (mb >= 1024.0) return "%.2f GB".format(mb / 1024.0)
    return "%.0f MB".format(mb)
}
