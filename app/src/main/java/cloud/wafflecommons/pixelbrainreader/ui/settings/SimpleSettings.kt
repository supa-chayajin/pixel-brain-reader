package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyLayoutViewModel

/** "Daily screen": which blocks are always shown; the others wait in the "More today" fold. */
@Composable
fun DailyScreenSettings(viewModel: DailyLayoutViewModel = hiltViewModel()) {
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    Column {
        Text(
            "Daily screen — always show",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            "Everything else waits in \"More today\", one tap away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DailySection.entries.forEach { section ->
            val checked = section in shown
            ListItem(
                modifier = Modifier.clickable(role = Role.Switch) { viewModel.setShown(section, !checked) },
                supportingContent = { Text(section.description) },
                trailingContent = {
                    Switch(checked = checked, onCheckedChange = { viewModel.setShown(section, it) })
                }
            ) {
                Text(section.label)
            }
        }
    }
}

@Composable
fun AdvancedSettingsHeader(expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Health Connect, Google, on-device AI, notes index, guard, RPG rules, import/export",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Fold" else "Unfold"
            )
        }
    }
}
