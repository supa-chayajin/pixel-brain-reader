package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.games.syncedLabel
import java.time.LocalDate

/**
 * Settings › Connections › Games (13.4, S8), one group: PlayStation and Xbox (each with its status,
 * opening its page; only in a build that carries its client), Game night (listed even in a build
 * with no console: what it does and its Do Not Disturb access), and "Unlock to start a session" for
 * the Quick Settings tile (G2, on by default). Nothing here writes the vault: the consoles' pages
 * say so themselves.
 */
@Composable
fun GamesSettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: GamesSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Back from the system's Do Not Disturb page or a console's sign-in: read them again.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() } }

    SettingsPageScaffold(page = SettingsPage.GAMES, onBack = onBack) {
        SettingsGroup {
            if (state.psnConfigured) {
                row {
                    SettingsRow(
                        title = SettingsPage.PSN.title,
                        description = consoleLine(state.psnConnected, state.psnStatus?.let { ConsoleSync(it.ok, it.atMillis, it.message) }, state.today),
                        icon = SettingsPage.PSN.icon,
                        onClick = { onOpen(SettingsPage.PSN.route) }
                    )
                }
            }
            if (state.xboxConfigured) {
                row {
                    SettingsRow(
                        title = SettingsPage.XBOX.title,
                        description = consoleLine(state.xboxConnected, state.xboxStatus?.let { ConsoleSync(it.ok, it.atMillis, it.message) }, state.today),
                        icon = SettingsPage.XBOX.icon,
                        onClick = { onOpen(SettingsPage.XBOX.route) }
                    )
                }
            }
            row {
                SettingsRow(
                    title = "Game night",
                    description = "A session started in ${state.personalLabel} mode outside work hours turns on the app's " +
                        "Game night mode, one of Android's Modes; set its exceptions in Android's Modes.",
                    icon = Icons.Rounded.DoNotDisturbOn,
                    anchor = SettingsAnchors.GAME_NIGHT,
                    below = {
                        when {
                            state.readOnly -> Text(
                                "Game sessions don't start on a read-only device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            state.dndGranted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("Allowed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            else -> FilledTonalButton(
                                onClick = {
                                    try {
                                        context.startActivity(viewModel.accessSettingsIntent())
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w(TAG, "No Do Not Disturb access page: ${e.javaClass.simpleName}")
                                    }
                                },
                                shapes = ButtonDefaults.shapes()
                            ) { Text("Allow Do Not Disturb access") }
                        }
                    }
                )
            }
            row {
                SettingsSwitchRow(
                    title = "Unlock to start a session",
                    checked = state.tileUnlock,
                    onCheckedChange = viewModel::setTileUnlock,
                    description = "A start from the Quick Settings tile on a locked phone asks for an unlock first. Stop and " +
                        "Keep playing stay one tap.",
                    icon = Icons.Rounded.LockClock,
                    // The tile is unavailable on a read-only device.
                    enabled = !state.readOnly,
                    anchor = SettingsAnchors.TILE_UNLOCK
                )
            }
        }
    }
}

/** A console's last sync, whichever console. */
private class ConsoleSync(val ok: Boolean, val atMillis: Long, val message: String)

/** A console's line: "Connected · last sync today 06:00", "Paused: …", or "Not connected". */
private fun consoleLine(connected: Boolean, sync: ConsoleSync?, today: LocalDate): String = when {
    !connected -> "Not connected"
    sync == null -> "Connected · no sync yet"
    sync.ok -> "Connected · last sync ${syncedLabel(sync.atMillis, today)}"
    sync.message.isBlank() -> "Paused"
    else -> "Paused: ${sync.message}"
}

private const val TAG = "GamesSettings"
