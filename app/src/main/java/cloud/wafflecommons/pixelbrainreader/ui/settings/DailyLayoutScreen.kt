package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyLayoutViewModel
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance

/**
 * Settings → Daily screen: the order of the Daily blocks and which ones stay on screen. The Daily
 * screen shows each group in this order; a block switched off waits in "More today".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLayoutScreen(
    onNavigateBack: () -> Unit,
    viewModel: DailyLayoutViewModel = hiltViewModel()
) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val onScreen = order.filter { it in shown }
    val folded = order.filter { it !in shown }

    val move: (DailySection, Boolean) -> Unit = { section, up ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.move(section, up)
    }
    val setShown: (DailySection, Boolean) -> Unit = { section, on ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.setShown(section, on)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily screen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            item(key = "intro") {
                Text(
                    text = "Put the blocks in the order you want with the arrows. Switch a block off and " +
                        "it waits in \"More today\", one tap away, in the same order. The mode chips, " +
                        "the Add box and the banners stay at the top.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item(key = "header-on-screen") { GroupTitle("On the Daily screen") }
            if (onScreen.isEmpty()) {
                item(key = "empty-on-screen") { GroupHint("Nothing on screen: switch a block on below.") }
            }
            itemsIndexed(onScreen, key = { _, s -> s.key }) { index, section ->
                DailyBlockRow(
                    section = section,
                    shown = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < onScreen.lastIndex,
                    onMove = { up -> move(section, up) },
                    onShownChange = { setShown(section, it) },
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "header-more-today") { GroupTitle("In \"More today\"") }
            if (folded.isEmpty()) {
                item(key = "empty-more-today") { GroupHint("Empty: every block is on screen.") }
            }
            itemsIndexed(folded, key = { _, s -> s.key }) { index, section ->
                DailyBlockRow(
                    section = section,
                    shown = false,
                    canMoveUp = index > 0,
                    canMoveDown = index < folded.lastIndex,
                    onMove = { up -> move(section, up) },
                    onShownChange = { setShown(section, it) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun GroupHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DailyBlockRow(
    section: DailySection,
    shown: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (up: Boolean) -> Unit,
    onShownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(section.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(enabled = canMoveUp, onClick = { onMove(true) }) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${section.label} up")
            }
            IconButton(enabled = canMoveDown, onClick = { onMove(false) }) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move ${section.label} down")
            }
            Switch(
                checked = shown,
                onCheckedChange = onShownChange,
                modifier = Modifier.semantics { contentDescription = "Keep ${section.label} on screen" }
            )
        }
    }
}
