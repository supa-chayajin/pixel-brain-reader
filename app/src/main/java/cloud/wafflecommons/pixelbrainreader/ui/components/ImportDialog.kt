package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportDialog(
    initialTitle: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (filename: String, folder: String, content: String) -> Unit
) {
    var filename by remember { mutableStateOf(initialTitle.ifBlank { "Untitled" }) }
    var folder by remember { mutableStateOf("00_Inbox") }
    var content by remember { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to Vault") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text("Filename (no ext)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Target Folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Content Preview:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Markdown") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    val cleanName = if (filename.endsWith(".md")) filename else "$filename.md"
                    onSave(cleanName, folder, content) 
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
