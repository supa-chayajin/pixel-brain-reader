package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.privatevault.AutoLock
import cloud.wafflecommons.pixelbrainreader.ui.privatevault.PrivateJournalActivity

/**
 * Settings › Privacy & safety › Private journal (13.4, S-h): the auto-lock (S4: also in the
 * journal's menu), whether private notes reach the AI (settled decision #20) and where the password
 * changes (the journal's menu, once unlocked: it needs the session). Nothing here writes the vault.
 */
@Composable
fun PrivateJournalSettingsScreen(onBack: () -> Unit, viewModel: PrivateJournalSettingsViewModel = hiltViewModel()) {
    val autoLock by viewModel.autoLockMinutes.collectAsStateWithLifecycle()
    val privateNotesInAi by viewModel.privateNotesInAi.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    val context = LocalContext.current

    SettingsPageScaffold(page = SettingsPage.PRIVATE_JOURNAL, onBack = onBack) {
        SettingsGroup {
            row {
                SettingsChoiceRow(
                    title = "Auto-lock",
                    choices = AUTO_LOCK_CHOICES.map { it.second },
                    selected = AUTO_LOCK_CHOICES.indexOfFirst { it.first == autoLock },
                    onSelect = { viewModel.setAutoLock(AUTO_LOCK_CHOICES[it].first) },
                    description = "How long the journal stays open after it leaves the screen",
                    icon = Icons.Rounded.LockClock,
                    anchor = SettingsAnchors.AUTO_LOCK
                )
            }
            row {
                SettingsSwitchRow(
                    title = "Private notes in AI answers",
                    checked = privateNotesInAi,
                    onCheckedChange = viewModel::setPrivateNotesInAi,
                    description = when {
                        readOnly -> "The AI is off on this read-only device."
                        privateNotesInAi -> "On: while the private journal is unlocked, its notes are embedded and Chat reads them."
                        else -> "Off: your private vault is never embedded, and Chat never reads it."
                    },
                    icon = Icons.Rounded.Psychology,
                    enabled = !readOnly,
                    anchor = SettingsAnchors.PRIVATE_AI
                )
            }
            row {
                SettingsRow(
                    title = "Change password",
                    description = if (readOnly) {
                        "Read-only device: change it on your phone."
                    } else {
                        "In the journal's menu, once it's unlocked"
                    },
                    icon = Icons.Rounded.Password,
                    anchor = SettingsAnchors.PASSWORD,
                    enabled = !readOnly,
                    onClick = { context.startActivity(Intent(context, PrivateJournalActivity::class.java)) }
                )
            }
        }
    }
}

/** [AutoLock.CHOICES], each with its label on the connected buttons. */
private val AUTO_LOCK_CHOICES: List<Pair<Int, String>> = AutoLock.CHOICES.map { minutes ->
    minutes to if (minutes == 0) "At once" else "$minutes min"
}
