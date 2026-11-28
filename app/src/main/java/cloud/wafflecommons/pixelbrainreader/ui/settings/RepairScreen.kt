@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import kotlinx.coroutines.launch

/**
 * Settings › Vault & device › Repair (13.4, S7): the 12.x Import / Export All buttons as two repair
 * tools, each behind a confirmation that says what it replaces and that changes already save on
 * their own. Writing back changes the vault: gone on a read-only device, which shows ReadOnlyNotice;
 * reloading only reads it and stays.
 */
@Composable
fun RepairScreen(onBack: () -> Unit, viewModel: RepairViewModel = hiltViewModel()) {
    val running by viewModel.running.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The tool waiting for its confirmation: a SettingsAnchors key.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPageScaffold(page = SettingsPage.REPAIR, onBack = onBack, snackbarHostState = snackbarHostState) {
        if (readOnly) ReadOnlyNotice()
        SettingsGroup {
            row { SettingsNote("Habits, rooms and chores save to the vault on their own. Use these only when something looks out of step.") }
            row {
                SettingsRow(
                    title = "Reload habits, rooms & chores from the vault",
                    description = "The app's copy is replaced by what the vault holds",
                    icon = Icons.Rounded.CloudDownload,
                    anchor = SettingsAnchors.RELOAD,
                    enabled = !running,
                    onClick = { confirming = SettingsAnchors.RELOAD }
                )
            }
            if (!readOnly) {
                row {
                    SettingsRow(
                        title = "Write them back to the vault",
                        description = "The vault's files are replaced by what the app holds, then the vault syncs",
                        icon = Icons.Rounded.CloudUpload,
                        anchor = SettingsAnchors.WRITE_BACK,
                        enabled = !running,
                        onClick = { confirming = SettingsAnchors.WRITE_BACK }
                    )
                }
            }
            if (running) {
                row {
                    SettingsRow(
                        title = "Working…",
                        icon = Icons.Rounded.Sync,
                        below = { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    )
                }
            }
        }
    }

    when (confirming) {
        SettingsAnchors.RELOAD -> RepairDialog(
            title = "Reload from the vault?",
            text = "The app's habits, habit logs, rooms and chores are replaced by what the vault holds. Changes already save on their own.",
            confirm = "Reload",
            onConfirm = {
                confirming = null
                viewModel.reload { ok ->
                    val message = if (ok) "Reloaded habits, rooms and chores from the vault." else "The reload failed."
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            },
            onDismiss = { confirming = null }
        )
        SettingsAnchors.WRITE_BACK -> RepairDialog(
            title = "Write back to the vault?",
            text = "The vault's habits, rooms and chores files are replaced by what the app holds, then the vault syncs. " +
                "Changes already save on their own.",
            confirm = "Write back",
            onConfirm = {
                confirming = null
                viewModel.writeBack { outcome ->
                    // A file left as it is asks for a reload first: long enough to be read.
                    val left = (outcome as? WriteBackOutcome.Done)?.left.orEmpty()
                    val duration = if (left.isEmpty()) SnackbarDuration.Short else SnackbarDuration.Long
                    scope.launch { snackbarHostState.showSnackbar(writeBackMessage(outcome), duration = duration) }
                }
            },
            onDismiss = { confirming = null }
        )
    }
}

@Composable
private fun RepairDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}
