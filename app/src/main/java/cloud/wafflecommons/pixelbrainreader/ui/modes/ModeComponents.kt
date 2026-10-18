package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeSource
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun ContextMode.label(): String = when (this) {
    ContextMode.WORK -> "Work"
    ContextMode.PERSONAL -> "Personal"
    ContextMode.OFF -> "Off"
}

fun ContextMode.icon(): ImageVector = when (this) {
    ContextMode.WORK -> Icons.Rounded.Work
    ContextMode.PERSONAL -> Icons.Rounded.Home
    ContextMode.OFF -> Icons.Rounded.Bedtime
}

private fun ContextMode.description(): String = when (this) {
    ContextMode.WORK -> "Everything visible; work notifications on."
    ContextMode.PERSONAL -> "Work folders hidden from lists, search, AI, widgets and notifications."
    ContextMode.OFF -> "Work fully muted — evenings, weekends and leave."
}

/** Why the mode is what it is, in one line. */
fun Visibility.sourceLine(): String = when (source) {
    ModeSource.MANUAL -> "Chosen manually until the next schedule change"
    ModeSource.LEAVE -> "On leave" + (leave?.let { " until ${shortDate(it.end)}" } ?: "")
    ModeSource.SCHEDULE -> "Set by your schedule"
    ModeSource.DEFAULT -> "No schedule set"
}

private fun shortDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}.getOrDefault(iso)

/** The current mode, as a chip that opens the mode sheet. */
@Composable
fun ModeChip(visibility: Visibility, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = visibility.mode.label() + if (visibility.screenShareSafe) " · Safe" else ""
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (visibility.screenShareSafe) Icons.Rounded.VisibilityOff else visibility.mode.icon(),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        modifier = modifier
    )
}

/** Pick a mode, toggle screen-share safe, or open the settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(
    visibility: Visibility,
    onSelect: (ContextMode) -> Unit,
    onFollowSchedule: () -> Unit,
    onScreenShareSafe: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(visibility.sourceLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            ContextMode.entries.forEach { mode ->
                ListItem(
                    headlineContent = { Text(mode.label()) },
                    supportingContent = { Text(mode.description()) },
                    leadingContent = { Icon(mode.icon(), contentDescription = null) },
                    trailingContent = { RadioButton(selected = visibility.mode == mode, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = visibility.mode == mode,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) }
                    )
                )
            }
            if (visibility.source == ModeSource.MANUAL) {
                TextButton(onClick = onFollowSchedule) { Text("Follow the schedule again") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Screen-share safe") },
                supportingContent = { Text("Hides work, private and health content everywhere — for demos and shared screens.") },
                leadingContent = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                trailingContent = { Switch(checked = visibility.screenShareSafe, onCheckedChange = onScreenShareSafe) }
            )
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Modes settings")
            }
        }
    }
}

/** Upcoming key dates with a countdown (today, tomorrow, in N days). */
@Composable
fun CountdownCard(countdowns: List<KeyDateCountdown>, modifier: Modifier = Modifier) {
    if (countdowns.isEmpty()) return
    SectionCard(title = "Key dates", icon = Icons.Rounded.Event, modifier = modifier) {
        countdowns.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(item.keyDate.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    when (item.daysLeft) {
                        0L -> "Today"
                        1L -> "Tomorrow"
                        else -> "in ${item.daysLeft} days"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (item.daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
