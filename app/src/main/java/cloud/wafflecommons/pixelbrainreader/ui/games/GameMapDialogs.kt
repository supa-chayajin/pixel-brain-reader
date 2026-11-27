package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.games.GameMaps
import cloud.wafflecommons.pixelbrainreader.data.games.MapEditResult

/**
 * Adds or changes a map (13.3, ME1–ME3): the address, an optional label, and what the map will
 * show as before it is saved. Only a public https address can be saved (the import guard's check,
 * [GameMaps.accepts]); the repository checks it again on the page read from disk.
 */
@Composable
fun MapEditDialog(
    title: String,
    initialUrl: String,
    initialLabel: String?,
    confirmLabel: String = "Save",
    onSave: (url: String, label: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var label by rememberSaveable { mutableStateOf(initialLabel.orEmpty()) }
    val preview = remember(url, label) { GameMaps.preview(url, label) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Address") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = url.isNotBlank() && preview == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    supportingText = { Text("What the page is, like Interactive map") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    preview != null -> Text(
                        "Opens on ${preview.site} as “${preview.label}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    url.isNotBlank() -> Text(
                        MapEditResult.NOT_PUBLIC_HTTPS.message(saved = ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url.trim(), GameMaps.cleanLabel(label)) },
                enabled = preview != null,
                shapes = ButtonDefaults.shapes()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        }
    )
}

/** What a map edit's outcome says on screen; [saved] is the line for a change that went through. */
fun MapEditResult.message(saved: String): String = when (this) {
    MapEditResult.SAVED -> saved
    MapEditResult.NOT_PUBLIC_HTTPS -> "Only a public https address can be a map."
    MapEditResult.ALREADY_THERE -> "That map is already on this game's page."
    MapEditResult.GONE -> "The page or the map changed meanwhile: look again."
    MapEditResult.READ_ONLY -> "Read-only device: change maps on your phone."
    MapEditResult.FAILED -> "The game's page couldn't be saved."
}
