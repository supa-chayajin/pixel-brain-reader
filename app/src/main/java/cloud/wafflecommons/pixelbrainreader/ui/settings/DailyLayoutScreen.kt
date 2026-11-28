package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderHandle
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderMath
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderActions
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderable
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.components.switchThumb
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyLayoutViewModel

/**
 * Settings › Daily screen: the order of the Daily blocks and which ones stay on screen. The Daily
 * screen shows each group in this order; a block switched off waits in "More today". 13.4 (S5): a
 * block's ≡ handle drags it within its group (long-press, then drag; the block is saved at its new
 * place at the drop), and a screen reader moves it with "Move up" and "Move down". The owner
 * arranges this screen: nothing here moves a block on its own.
 */
@Composable
fun DailyLayoutScreen(
    onNavigateBack: () -> Unit,
    viewModel: DailyLayoutViewModel = hiltViewModel()
) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val onScreen = order.filter { it in shown }
    val folded = order.filter { it !in shown }

    val moveTo: (DailySection, Int) -> Unit = { section, target -> viewModel.moveTo(section, target) }
    val onScreenDrag = rememberBlockDrag(onScreen, listState::scrollBy, moveTo)
    val foldedDrag = rememberBlockDrag(folded, listState::scrollBy, moveTo)
    val setShown: (DailySection, Boolean) -> Unit = { section, on ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.setShown(section, on)
    }

    SettingsLazyPageScaffold(page = SettingsPage.DAILY_LAYOUT, onBack = onNavigateBack, listState = listState) {
        item(key = "intro") {
            SettingsNote(
                "Long-press a block's ≡, then drag it. Switch a block off and it waits in \"More today\", in the " +
                    "same order. The mode chips, the Add box and the banners stay at the top."
            )
        }

        item(key = "header-on-screen") { SettingsSubhead("On the Daily screen", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        if (onScreenDrag.working.isEmpty()) {
            item(key = "empty-on-screen") { SettingsNote("Nothing on screen: switch a block on below.") }
        }
        itemsIndexed(onScreenDrag.working, key = { _, s -> s.key }) { index, section ->
            DailyBlockRow(
                section = section,
                shown = true,
                index = index,
                drag = onScreenDrag,
                onShownChange = { setShown(section, it) },
                // The dragged block follows the finger, never animated; the others slide into their new places.
                modifier = if (onScreenDrag.state.draggedKey == section.key) Modifier.animateItem(placementSpec = null) else Modifier.animateItem()
            )
        }

        item(key = "header-more-today") { SettingsSubhead("In \"More today\"", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        if (foldedDrag.working.isEmpty()) {
            item(key = "empty-more-today") { SettingsNote("Empty: every block is on screen.") }
        }
        itemsIndexed(foldedDrag.working, key = { _, s -> s.key }) { index, section ->
            DailyBlockRow(
                section = section,
                shown = false,
                index = index,
                drag = foldedDrag,
                onShownChange = { setShown(section, it) },
                modifier = if (foldedDrag.state.draggedKey == section.key) Modifier.animateItem(placementSpec = null) else Modifier.animateItem()
            )
        }
    }
}

/**
 * One group's drag: its blocks as a working copy while a drag runs; at the drop, the block that
 * moved is saved at its new place in the group ([onDrop]), once.
 */
private class BlockDrag(
    val working: List<DailySection>,
    val state: ReorderState,
    val onMove: (Int, Int) -> Unit,
    val onDrop: () -> Unit
)

@Composable
private fun rememberBlockDrag(
    group: List<DailySection>,
    scrollBy: suspend (Float) -> Float,
    onDrop: (DailySection, Int) -> Unit
): BlockDrag {
    var working by remember(group) { mutableStateOf(group) }
    // The block this drag (or this screen reader's step) moves: the one at the first move's start.
    var moving by remember { mutableStateOf<DailySection?>(null) }
    val onMove: (Int, Int) -> Unit = { from, to ->
        if (moving == null) moving = working.getOrNull(from)
        working = ReorderMath.moved(working, from, to)
    }
    val drop: () -> Unit = {
        moving?.let { section -> working.indexOf(section).takeIf { it >= 0 }?.let { onDrop(section, it) } }
        moving = null
    }
    val state = rememberReorderState(onMove = onMove, onDrop = drop, scrollBy = scrollBy)
    return BlockDrag(working, state, onMove, drop)
}

@Composable
private fun DailyBlockRow(
    section: DailySection,
    shown: Boolean,
    index: Int,
    drag: BlockDrag,
    onShownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = drag.working.size
    val dragged = drag.state.draggedKey == section.key
    Surface(
        shape = segmentShape(index, count),
        color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (dragged) 4.dp else 0.dp,
        // reorderable lifts the dragged block above its neighbours (and while it settles back).
        modifier = modifier
            .fillMaxWidth()
            .reorderable(drag.state, section.key, index)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The block's name and the screen reader's "Move up" / "Move down" as one item.
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {}
                    .reorderActions(index, count, drag.onMove, drag.onDrop)
            ) {
                ReorderHandle(state = drag.state, key = section.key, index = index)
                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(section.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        section.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = shown,
                thumbContent = switchThumb(shown),
                onCheckedChange = onShownChange,
                modifier = Modifier.semantics { contentDescription = "Keep ${section.label} on screen" }
            )
        }
    }
}
