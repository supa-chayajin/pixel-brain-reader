package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate

/**
 * Settings › Vault & device › This device & version (13.4, S-i): the app's version, this device's
 * role (chosen at login: changing it means logging out, which stays on the home) and its vault.
 */
@Composable
fun DeviceScreen(onBack: () -> Unit, viewModel: DeviceViewModel = hiltViewModel()) {
    val vault by viewModel.vault.collectAsStateWithLifecycle()

    SettingsPageScaffold(page = SettingsPage.DEVICE, onBack = onBack) {
        SettingsGroup {
            row {
                SettingsRow(
                    title = "Version",
                    description = "Pixel Brain Reader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    icon = Icons.Rounded.Info,
                    anchor = SettingsAnchors.VERSION
                )
            }
            row {
                SettingsRow(
                    title = "Device role",
                    description = if (VaultWriteGate.readOnly) {
                        "Read-only device: it reads the vault and never changes it. Log out to change this."
                    } else {
                        "This device writes the vault"
                    },
                    icon = Icons.Rounded.PhoneAndroid,
                    anchor = SettingsAnchors.ROLE
                )
            }
            row {
                SettingsRow(
                    title = "Vault",
                    description = when (val repository = vault) {
                        DeviceVault.Reading -> "Reading…"
                        is DeviceVault.Saved -> repository.name
                        DeviceVault.None -> "No repository saved"
                        DeviceVault.Unreadable -> "The repository couldn't be read"
                    },
                    icon = Icons.Rounded.Storage
                )
            }
        }
    }
}
