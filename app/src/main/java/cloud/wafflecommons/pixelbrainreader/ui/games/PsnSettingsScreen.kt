@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnClient
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard

/**
 * PlayStation Network (RFC-009 §7): connect with an NPSSO (used once, never kept), see the last sync
 * and when the sign-in expires, sync now, disconnect; and the Do Not Disturb access a game night needs.
 * 13.4: the last sync says its day, the mode is called by the owner's own name for it, and Connect
 * clears the clipboard when it still holds the NPSSO.
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
                    // While a sync runs (the first one included), not the time of the one before.
                    when {
                        state.busy -> "Syncing…"
                        status == null -> "no sync yet"
                        status.ok -> "Last sync ${syncedLabel(status.atMillis, state.today)}"
                        else -> "Paused: ${status.message}"
                    },
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

        if (!state.readOnly && (!state.connected || state.status?.needsNpsso == true)) {
            SectionCard(
                title = if (state.connected) "Paste a new NPSSO" else "Connect",
                icon = Icons.Rounded.SportsEsports,
                subtitle = "1. Sign in on playstation.com in your browser. 2. Open the NPSSO page: it shows {\"npsso\":\"…\"}. " +
                    "3. Copy the value and paste it here. It is used once and never saved; the app keeps only the sign-in it gives, " +
                    "and clears the clipboard after Connect."
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
                Button(
                    onClick = {
                        // Read before Connect empties the field: the clip is compared with what was typed.
                        val typed = viewModel.pasted
                        viewModel.connect()
                        clearNpssoClip(context, typed)
                    },
                    shapes = ButtonDefaults.shapes(),
                    enabled = !state.busy && viewModel.pasted.isNotBlank()
                ) { Text("Connect") }
            }
        }

        SectionCard(
            title = "Game night",
            icon = Icons.Rounded.DoNotDisturbOn,
            subtitle = if (state.dndGranted) {
                "Allowed. A session started in ${state.personalLabel} mode, outside work hours, turns on the \"Game night\" mode; " +
                    "set its exceptions in Settings › Modes."
            } else {
                "A session started in ${state.personalLabel} mode, outside work hours, turns on the app's \"Game night\" mode. " +
                    "It needs Do Not Disturb access."
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

/**
 * True when the clipboard's text [clip] holds the NPSSO [typed] (pure, 13.4): both read the way
 * Connect reads a paste ([PsnClient.cleanNpsso]: the value alone, or the NPSSO page's whole answer),
 * equal, and an NPSSO at all. Anything else on the clipboard is left alone.
 */
internal fun clipHoldsNpsso(clip: String?, typed: String): Boolean {
    if (clip == null) return false
    val inClip = PsnClient.cleanNpsso(clip) ?: return false
    return inClip == PsnClient.cleanNpsso(typed)
}

/**
 * After Connect, whatever its outcome (13.4, the audit's npsso-left-on-clipboard): the NPSSO is the
 * PlayStation account's web session, so the clipboard is cleared while it still holds the value
 * typed, and only then, never another clip. Read at the tap, while the app has the focus the system
 * asks for. The keyboard's own clipboard history is out of the app's reach: the toast says so.
 */
private fun clearNpssoClip(context: Context, typed: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = try {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    } catch (e: SecurityException) {
        null
    }
    if (!clipHoldsNpsso(clip, typed)) return
    clipboard.clearPrimaryClip()
    Toast.makeText(context, "Clipboard cleared. Your keyboard's own clipboard history may still keep a copy.", Toast.LENGTH_LONG).show()
}
