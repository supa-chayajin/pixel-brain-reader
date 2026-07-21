package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance

/** Route → (icon, label) for the reorderable regular nav destinations. */
private val NAV_ITEM_META: Map<String, Pair<ImageVector, String>> = mapOf(
    "home" to (Icons.Rounded.Dashboard to "Dépôt"),
    "habits" to (Icons.Rounded.DateRange to "Habitudes"),
    "home_os" to (Icons.Rounded.CleaningServices to "Corvées"),
    "chat" to (Icons.Rounded.Psychology to "Chat"),
    "mood" to (Icons.Default.Mood to "Humeur"),
    "stats" to (Icons.Default.Star to "Stats")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBarReorderScreen(
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
    onNavigateBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ordre de la barre de navigation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Réorganisez les onglets. Le bouton « Quotidien » reste fixe. " +
                        "Sur les écrans compacts, seuls les trois premiers sont affichés.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            itemsIndexed(order, key = { _, route -> route }) { index, route ->
                val (icon, label) = NAV_ITEM_META[route] ?: (Icons.Rounded.Dashboard to route)
                ElevatedCard(modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        // Move up
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOrderChange(order.swapped(index, index - 1))
                            }
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Monter")
                        }
                        // Move down
                        IconButton(
                            enabled = index < order.lastIndex,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOrderChange(order.swapped(index, index + 1))
                            }
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Descendre")
                        }
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> {
    if (i !in indices || j !in indices) return this
    return toMutableList().also { it[i] = this[j]; it[j] = this[i] }
}
