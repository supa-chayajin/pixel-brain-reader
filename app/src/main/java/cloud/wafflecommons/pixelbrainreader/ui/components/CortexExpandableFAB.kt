package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

data class FabActionItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/**
 * Expandable FAB backed by the stock Material 3 Expressive [FloatingActionButtonMenu] +
 * [ToggleFloatingActionButton]: the button morphs and the labelled action items stagger in
 * with the official Expressive motion. Public API is unchanged so callers don't need edits.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CortexExpandableFAB(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<FabActionItem>,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onExpandedChange(it)
                }
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = if (expanded) "Close menu" else "Open menu"
                )
            }
        }
    ) {
        items.forEach { item ->
            FloatingActionButtonMenuItem(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onExpandedChange(false)
                    item.onClick()
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                text = { Text(item.label) }
            )
        }
    }
}
