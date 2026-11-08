package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The read-only tripwire (SyncOrchestrator.readOnlyDrift): this device's copy differs from GitHub
 * although it never writes. Its sync stays stopped until it is reset to GitHub, which discards the
 * listed changes — changes a read-only device should never have made.
 */
@Composable
fun ReadOnlyDriftBanner(changes: List<String>, onReset: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val onError = MaterialTheme.colorScheme.onErrorContainer
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = onError)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "This read-only device's copy differs from GitHub. Sync is stopped.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = onError,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = changes.take(MAX_SHOWN).joinToString("\n") +
                    if (changes.size > MAX_SHOWN) "\n+ ${changes.size - MAX_SHOWN} more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = onError,
                modifier = Modifier.padding(start = 36.dp, end = 8.dp)
            )
            TextButton(onClick = { confirming = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Reset to GitHub")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset to GitHub?") },
            text = {
                Text(
                    "This device's copy becomes GitHub's again and the changes listed are discarded. " +
                        "Nothing on GitHub or on your phone changes."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onReset()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

private const val MAX_SHOWN = 5
