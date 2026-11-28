@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.persistableBundleOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxClient
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Xbox (13.3, XB-E): Connect with the owner's own Microsoft app by device code (the code shown
 * here, typed at microsoft.com/link on any device), the last sync and how long the sign-in lasts,
 * Sync now and Disconnect. On a read-only device the page only says so: no Connect. 13.4: the last
 * sync says its day; a sync running anywhere shows as "Syncing…".
 */
@Composable
fun XboxSettingsScreen(onBack: () -> Unit, viewModel: XboxSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirmDisconnect by remember { mutableStateOf(false) }
    // Back from microsoft.com/link in the browser: read the sign-in again.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() } }

    FormScaffold(title = "Xbox", icon = Icons.Rounded.Gamepad, onBack = onBack) {
        if (state.readOnly) ReadOnlyNotice()
        if (!state.configured) {
            EmptyHint(
                "This build has no Xbox app: register one (the how-to in documentation), add xboxClientId to " +
                    "local.properties, then build again. Games works by hand without it."
            )
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
                    // While a sync runs (the first one included), not the time of the one before. 13.4: the
                    // last sync says its day, so one that stopped days ago never reads like last night's.
                    when {
                        state.busy -> "Syncing…"
                        status == null -> "no sync yet"
                        status.ok -> "Last sync ${syncedLabel(status.atMillis, state.today)}"
                        else -> "Paused: ${status.message}"
                    },
                    state.validUntil?.let { "renewed at each sync, good until ${shortDate(it)} without one" },
                    "syncs at 23:40 and 06:00"
                ).joinToString(" · ")
            ) {
                if (!state.readOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::syncNow, shapes = ButtonDefaults.shapes(), enabled = !state.busy) { Text("Sync now") }
                        OutlinedButton(onClick = { confirmDisconnect = true }, shapes = ButtonDefaults.shapes(), enabled = !state.busy) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        if (!state.readOnly && (!state.connected || state.status?.needsSignIn == true)) {
            SectionCard(
                title = if (state.connected) "Sign in again" else "Connect",
                icon = Icons.Rounded.Gamepad,
                subtitle = "Connect shows a code. Open microsoft.com/link on any device, type the code, and sign in with the " +
                    "account your Xbox uses. Only type a code this page showed you a moment ago."
            ) {
                val code = state.code
                if (code == null) {
                    Button(onClick = viewModel::connect, shapes = ButtonDefaults.shapes(), enabled = !state.busy) { Text("Connect") }
                } else {
                    SelectionContainer {
                        Text(code.userCode, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openLinkPage(context) }, shapes = ButtonDefaults.shapes()) { Text("Open microsoft.com/link") }
                        OutlinedButton(onClick = { copyCode(context, code.userCode) }, shapes = ButtonDefaults.shapes()) { Text("Copy the code") }
                    }
                    Text(
                        "Waiting for the sign-in… the code expires at ${clock(code.expiresAt)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = viewModel::cancelConnect, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Xbox?") },
            text = {
                Text(
                    "The sign-in is forgotten and the nightly sync stops for Xbox. Your games, sessions and " +
                        "achievements stay in the vault."
                )
            },
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

/** microsoft.com/link itself, never its pre-filled `?otc=` form (a consent error in 2026): the owner types the code. */
private fun openLinkPage(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, XboxClient.LINK_PAGE.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The code on the clipboard, marked sensitive so the system's preview and keyboards don't show it. */
private fun copyCode(context: Context, code: String) {
    val clip = ClipData.newPlainText("Xbox code", code).apply {
        description.extras = persistableBundleOf(ClipDescription.EXTRA_IS_SENSITIVE to true)
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun shortDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
