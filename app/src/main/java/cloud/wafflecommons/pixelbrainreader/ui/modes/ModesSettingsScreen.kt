package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsGroup
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPage
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPageScaffold
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsRow
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsRows
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsSwitchRow
import java.time.LocalDate

/**
 * Settings › Modes (13.4, S2): a hub of short pages. Each row shows what its page holds now
 * ([ModesSummary]) and opens it: Names & icons, What counts as work, the Personal mode's projects
 * (under the owner's name for that mode), Schedule, Shared custody, Leave & key dates. Screen-share
 * safe stays a switch here, a setting of this device. The tabs each mode shows moved to Settings ›
 * Navigation (S3).
 */
@Composable
fun ModesSettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}, viewModel: ModesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val appearance = config.appearance()

    SettingsPageScaffold(page = SettingsPage.MODES, onBack = onBack, appearance = appearance) {
        // The modes live in the vault: a read-only device shows them, and changes only screen-share
        // safe, a setting of its own (12.15, S2).
        if (VaultWriteGate.readOnly) ReadOnlyNotice()
        SettingsGroup {
            hubRow(SettingsPage.MODES_NAMES, ModesSummary.names(appearance), appearance, onOpen)
            hubRow(SettingsPage.MODES_WORK, ModesSummary.work(config), appearance, onOpen)
            hubRow(SettingsPage.MODES_PROJECTS, ModesSummary.projects(config, appearance), appearance, onOpen)
            hubRow(SettingsPage.MODES_SCHEDULE, ModesSummary.schedule(config, appearance), appearance, onOpen)
            hubRow(SettingsPage.MODES_CUSTODY, ModesSummary.custody(config, LocalDate.now()), appearance, onOpen)
            hubRow(SettingsPage.MODES_DATES, ModesSummary.dates(config), appearance, onOpen)
            row {
                SettingsSwitchRow(
                    title = "Screen-share safe",
                    checked = visibility.screenShareSafe,
                    onCheckedChange = viewModel::setScreenShareSafe,
                    description = "Hides work, private and health content everywhere until you turn it off",
                    icon = Icons.Rounded.VisibilityOff
                )
            }
        }
    }
}

/** A row of the hub: the page's own title and icon, what it holds now, a tap to open it. */
private fun SettingsRows.hubRow(page: SettingsPage, summary: String, appearance: ModeAppearance, onOpen: (String) -> Unit) = row {
    SettingsRow(
        title = page.titleFor(appearance),
        description = summary,
        icon = page.icon,
        onClick = { onOpen(page.route) }
    )
}
