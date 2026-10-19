package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeSource
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val label = visibility.label() + if (visibility.screenShareSafe) " · Safe" else ""
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (visibility.screenShareSafe) Icons.Rounded.VisibilityOff else ModeIcons.of(visibility.appearance, visibility.mode),
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
                    headlineContent = { Text(visibility.label(mode)) },
                    supportingContent = { Text(mode.description()) },
                    leadingContent = { Icon(ModeIcons.of(visibility.appearance, mode), contentDescription = null) },
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

/** Rename a mode and pick its icon; "Reset" brings the default name and icon back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeStyleDialog(
    mode: ContextMode,
    appearance: ModeAppearance,
    onConfirm: (ModeStyle) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(appearance.label(mode)) }
    var iconKey by remember { mutableStateOf(appearance.iconKey(mode)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${ModeAppearance.defaultLabel(mode)} mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(ModeAppearance.MAX_LABEL) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeIcons.all.forEach { (key, icon) ->
                        FilledIconToggleButton(checked = key == iconKey, onCheckedChange = { iconKey = key }) {
                            Icon(icon, contentDescription = key)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = {
                val name = label.trim()
                onConfirm(
                    ModeStyle(
                        label = if (name == ModeAppearance.defaultLabel(mode)) "" else name,
                        icon = if (iconKey == ModeAppearance.defaultIcon(mode)) "" else iconKey
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(ModeStyle()) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
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

/**
 * Mood and health stay off screen while screen-share safe is on: [content] shows only when
 * [hidden] is false, a placeholder with a way out otherwise.
 */
@Composable
fun HealthPrivacyGate(hidden: Boolean, onReveal: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!hidden) {
        content()
        return
    }
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.size(16.dp))
        Text("Hidden while screen-share safe is on", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            "Mood and health figures stay off screen until you turn it off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(16.dp))
        OutlinedButton(onClick = onReveal) { Text("Turn off screen-share safe") }
    }
}
