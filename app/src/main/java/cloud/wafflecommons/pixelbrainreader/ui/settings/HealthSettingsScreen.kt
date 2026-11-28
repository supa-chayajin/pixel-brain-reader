@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import kotlinx.coroutines.launch

/**
 * Settings › Connections › Health Connect (13.4, S-b): whether the app may read the phone's sleep,
 * steps and heart figures, and the button that asks for every permission it needs. Re-checked each
 * time the page comes back. A read-only device never reads Health Connect: the phone writes the
 * day's figures to the vault.
 */
@Composable
fun HealthSettingsScreen(onBack: () -> Unit, viewModel: HealthSettingsViewModel = hiltViewModel()) {
    val status = viewModel.status.collectAsStateWithLifecycle().value
    val readOnly = VaultWriteGate.readOnly
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permissions = remember(viewModel) { viewModel.permissions() }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        viewModel.check()
        if (granted.containsAll(permissions)) {
            viewModel.syncNow()
            scope.launch { snackbarHostState.showSnackbar("Health Connect connected") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Some permissions weren't granted") }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.check()
        onPauseOrDispose { }
    }
    val canConnect = !readOnly && !status.granted && status.sdk == HealthConnectClient.SDK_AVAILABLE

    SettingsPageScaffold(page = SettingsPage.HEALTH, onBack = onBack, snackbarHostState = snackbarHostState) {
        SettingsGroup {
            row {
                SettingsNote(
                    "Daily, Life Stats and the health habits read your sleep, steps and heart figures from Health " +
                        "Connect, on this phone. Each day's figures are kept in your vault."
                )
            }
            row {
                SettingsRow(
                    title = SettingsPage.HEALTH.title,
                    description = healthStatusLine(status),
                    icon = SettingsPage.HEALTH.icon,
                    trailing = if (status.granted) {
                        { Icon(Icons.Rounded.CheckCircle, contentDescription = "Connected", tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    below = if (canConnect) {
                        {
                            // Every permission the "Connected" check requires: asking for three left the
                            // row stuck on "Connect" and most figures at 0.
                            Button(onClick = { launcher.launch(permissions) }, shapes = ButtonDefaults.shapes()) {
                                Text("Connect")
                            }
                        }
                    } else {
                        null
                    }
                )
            }
            if (readOnly) {
                row { SettingsNote("Health Connect isn't read on a read-only device: your phone writes the day's figures.") }
            }
        }
    }
}

private fun healthStatusLine(status: HealthStatus): String = when {
    status.granted -> "Connected"
    status.sdk == 0 -> "Checking…"
    status.sdk == HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect isn't installed"
    status.sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update"
    else -> "Not connected"
}
