@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import androidx.compose.material.icons.rounded.VideogameAsset

/**
 * PlayStation Network (RFC-009 §7): connect with an NPSSO (used once, never kept), see the last sync
 * and when the sign-in expires, sync now, disconnect; and the Do Not Disturb access a game night needs.
 */
@Composable
fun PsnSettingsScreen(onBack: () -> Unit, viewModel: PsnSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirmDisconnect by remember { mutableStateOf(false) }
    // Back from the system's Do Not Disturb page: read the access again.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() } }

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    FormScaffold(title = "PlayStation Network", icon = Icons.Rounded.VideogameAsset, onBack = onBack) {
        if (state.readOnly) ReadOnlyNotice()
        if (!state.configured) {
            EmptyHint("This build has no PlayStation client: add psnClientId and psnClientSecret to local.properties, then build again. Games works by hand without it.")
            return@FormScaffold
        }
        if (state.busy) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (state.connected) {
            val status = state.status
            SectionCard(
                title = "Connected",
                icon = Icons.Rounded.SportsEsports,
                subtitle = listOfNotNull(
                    status?.let { if (it.ok) "Last sync ${clock(it.atMillis)}" else "Paused: ${it.message}" },
                    state.expiresInDays?.let { "the sign-in expires in $it days" },
                    "syncs at 23:40 and 06:00"
                ).joinToString(" · ")
            ) {
                if (!state.readOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::syncNow, shapes = ButtonDefaults.shapes(), enabled = !state.busy) { Text("Sync now") }
                        OutlinedButton(onClick = { confirmDisconnect = true }, shapes = ButtonDefaults.shapes(), enabled = !state.busy) { Text("Disconnect") }
                    }
                }
            }
        }

        if (!state.readOnly && (!state.connected || state.status?.needsOwner == true)) {
            SectionCard(
                title = if (state.connected) "Paste a new NPSSO" else "Connect",
                icon = Icons.Rounded.SportsEsports,
                subtitle = "1. Sign in on playstation.com in your browser. 2. Open the NPSSO page: it shows {\"npsso\":\"…\"}. " +
                    "3. Copy the value and paste it here. It is used once and never saved; the app keeps only the sign-in it gives."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open("https://www.playstation.com/") }, shapes = ButtonDefaults.shapes()) { Text("playstation.com") }
                    OutlinedButton(onClick = { open("https://ca.account.sony.com/api/v1/ssocookie") }, shapes = ButtonDefaults.shapes()) { Text("NPSSO page") }
                }
                OutlinedTextField(
                    value = viewModel.pasted,
                    onValueChange = { viewModel.pasted = it },
                    label = { Text("NPSSO") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = viewModel::connect, shapes = ButtonDefaults.shapes(), enabled = !state.busy && viewModel.pasted.isNotBlank()) { Text("Connect") }
            }
        }

        SectionCard(
            title = "Game night",
            icon = Icons.Rounded.DoNotDisturbOn,
            subtitle = if (state.dndGranted) {
                "Allowed. A session started in Perso, outside work hours, turns on the \"Game night\" mode; set its exceptions in Settings › Modes."
            } else {
                "A session started in Perso, outside work hours, turns on the app's \"Game night\" mode. It needs Do Not Disturb access."
            }
        ) {
            if (!state.dndGranted && !state.readOnly) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, shapes = ButtonDefaults.shapes()) { Text("Allow Do Not Disturb access") }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect PlayStation?") },
            text = { Text("The sign-in is forgotten and the nightly sync stops. Your games, sessions and trophies stay in the vault.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    viewModel.disconnect()
                }, shapes = ButtonDefaults.shapes()) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
        )
    }
}
