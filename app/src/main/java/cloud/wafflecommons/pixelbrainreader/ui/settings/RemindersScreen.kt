package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    viewModel: RemindersViewModel = hiltViewModel()
) {
    val vaultEnabled by viewModel.vaultEnabled.collectAsStateWithLifecycle()
    val vaultTime by viewModel.vaultTime.collectAsStateWithLifecycle()
    val choresEnabled by viewModel.choresEnabled.collectAsStateWithLifecycle()
    val choresWindows by viewModel.choresWindows.collectAsStateWithLifecycle()

    // When set, holds the initial "HH:mm" and the callback to run on confirm.
    var timePicker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = {
                    CortexIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- Private vault reminder ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowSwitch(
                        title = "🔒 Private vault reminder",
                        subtitle = "Daily nudge to write — skipped if you already wrote today.",
                        checked = vaultEnabled,
                        onCheckedChange = viewModel::setVaultEnabled
                    )
                    if (vaultEnabled) {
                        ListItem(
                            modifier = Modifier.clickable {
                                timePicker = vaultTime to { hhmm -> viewModel.setVaultTime(hhmm) }
                            },
                            headlineContent = { Text("Reminder time") },
                            trailingContent = {
                                Text(vaultTime, style = MaterialTheme.typography.titleMedium)
                            }
                        )
                    }
                }
            }

            // --- Chores & habits reminder ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowSwitch(
                        title = "🧹 Chores & habits reminder",
                        subtitle = "At each window, get a summary of due chores and unfinished habits.",
                        checked = choresEnabled,
                        onCheckedChange = viewModel::setChoresEnabled
                    )
                    if (choresEnabled) {
                        Text("Time windows", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            choresWindows.forEach { win ->
                                InputChip(
                                    selected = false,
                                    onClick = { viewModel.removeChoresWindow(win) },
                                    label = { Text(win) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove $win",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                        OutlinedButton(onClick = {
                            timePicker = "09:00" to { hhmm -> viewModel.addChoresWindow(hhmm) }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add window")
                        }
                    }
                }
            }
        }
    }

    timePicker?.let { (initial, onConfirm) ->
        TimePickerDialog(
            initial = initial,
            onConfirm = { hhmm ->
                onConfirm(hhmm)
                timePicker = null
            },
            onDismiss = { timePicker = null }
        )
    }
}

@Composable
private fun RowSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Material 3 time picker wrapped in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val parts = initial.split(":")
    val state = rememberTimePickerState(
        initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 20,
        initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Select time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        onConfirm("%02d:%02d".format(state.hour, state.minute))
                    }) { Text("OK") }
                }
            }
        }
    }
}
