package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeTabs
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.ui.components.ExpressiveBadge
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderHandle
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderMath
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.ShowsInButtons
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderActions
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderable
import cloud.wafflecommons.pixelbrainreader.ui.main.NavTabs
import cloud.wafflecommons.pixelbrainreader.ui.main.Screen

/**
 * Settings › Navigation (13.4, S3, S5): one page for the tabs. Their order by drag (long-press a
 * tab's ≡, or "Move up" and "Move down" for a screen reader), saved once at the drop; the modes each
 * tab shows in (Modes' "Tabs per mode" moved here: the vault's Modes file, so off on a read-only
 * device); the toolkit's chip on Daily; the rail's names. The order and the rail are this device's
 * own and stay usable on a read-only device.
 */
@Composable
fun NavigationSettingsScreen(onBack: () -> Unit, viewModel: NavigationSettingsViewModel = hiltViewModel()) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val railExpanded by viewModel.railExpanded.collectAsStateWithLifecycle()
    val appearance = config.appearance()
    val readOnly = VaultWriteGate.readOnly

    // The saved order, tabs this version draws only; a working copy while a drag runs, saved at the drop.
    val saved = order.filter { NavTabs.of(it) != null }
    var working by remember(saved) { mutableStateOf(saved) }
    val onMove: (Int, Int) -> Unit = { from, to -> working = ReorderMath.moved(working, from, to) }
    val onDrop: () -> Unit = { viewModel.setOrder(working) }

    SettingsPageScaffold(page = SettingsPage.NAVIGATION, onBack = onBack, appearance = appearance) {
        // Inside the frame, which hands down its scroll: a tab dragged near the page's edge scrolls it.
        val reorder = rememberReorderState(onMove = onMove, onDrop = onDrop, scrollBy = rememberSettingsScrollBy())
        SettingsGroup {
            row { SettingsNote("Long-press a tab's ≡, then drag it. On a phone the bar shows the first four; Daily keeps its own button.") }
        }
        // The modes each tab shows in live in the vault: off here, the order and the rail still usable.
        if (readOnly) ReadOnlyNotice()
        SettingsGroup {
            working.forEachIndexed { index, key ->
                val tab = NavTabs.of(key) ?: return@forEachIndexed
                row {
                    TabLine(
                        label = tab.label,
                        icon = tab.icon,
                        note = if (key == Screen.Chat && readOnly) "Off on this read-only device" else null,
                        shown = shownIn(config, key),
                        appearance = appearance,
                        readOnly = readOnly,
                        onShownIn = { viewModel.setShownIn(key, it) },
                        rowAnchor = if (index == 0) SettingsAnchors.TAB_ORDER else null,
                        showsInAnchor = if (index == 0) SettingsAnchors.SHOWS_IN else null,
                        drag = TabDrag(reorder, key, index, working.size, onMove, onDrop)
                    )
                }
            }
            row {
                TabLine(
                    label = NavTabs.label(ModeTabs.TOOLKIT, appearance.toolkitName),
                    icon = Icons.Rounded.Apps,
                    note = null,
                    shown = shownIn(config, ModeTabs.TOOLKIT),
                    appearance = appearance,
                    readOnly = readOnly,
                    onShownIn = { viewModel.setShownIn(ModeTabs.TOOLKIT, it) },
                    rowAnchor = null,
                    showsInAnchor = null,
                    drag = null
                )
            }
        }
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = "Tab names on the rail",
                    checked = railExpanded,
                    onCheckedChange = viewModel::setRailExpanded,
                    description = "A wide window's rail shows each tab's name",
                    icon = Icons.AutoMirrored.Rounded.ViewSidebar,
                    anchor = SettingsAnchors.RAIL_NAMES
                )
            }
        }
    }
}

/** The modes a tab shows in: every mode not hiding it. */
private fun shownIn(config: ModesConfig, key: String): Set<ContextMode> =
    ContextMode.entries.filter { key !in config.hiddenTabs[it].orEmpty() }.toSet()

/** What a tab's line needs to be dragged: its place in the working order and the moves it makes. */
private class TabDrag(
    val state: ReorderState,
    val key: String,
    val index: Int,
    val count: Int,
    val onMove: (Int, Int) -> Unit,
    val onDrop: () -> Unit
)

/**
 * A tab in the list: its ≡ handle (none for the toolkit's chip, which has no place in the bar), its
 * emblem and name, then the modes it shows in. Raised while it is dragged.
 */
@Composable
private fun TabLine(
    label: String,
    icon: ImageVector,
    note: String?,
    shown: Set<ContextMode>,
    appearance: ModeAppearance,
    readOnly: Boolean,
    onShownIn: (Set<ContextMode>) -> Unit,
    rowAnchor: String?,
    showsInAnchor: String?,
    drag: TabDrag?
) {
    val dragged = drag != null && drag.state.draggedKey == drag.key
    // reorderable lifts the dragged line above its neighbours (and while it settles back).
    val lineModifier = Modifier
        .fillMaxWidth()
        .let { if (drag != null) it.reorderable(drag.state, drag.key, drag.index) else it }
        .settingsAnchor(rowAnchor)
    Surface(
        shape = LocalSegmentPosition.current.shape,
        color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (dragged) 4.dp else 0.dp,
        modifier = lineModifier
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Its name and the screen reader's "Move up" / "Move down" as one item; the handle is silent.
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .let { if (drag != null) it.reorderActions(drag.index, drag.count, drag.onMove, drag.onDrop) else it }
            ) {
                if (drag != null) {
                    ReorderHandle(state = drag.state, key = drag.key, index = drag.index)
                } else {
                    Spacer(Modifier.size(HANDLE_WIDTH))
                }
                ExpressiveBadge(icon = icon, key = label, size = 36.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (note != null) {
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ShowsInButtons(
                shown = shown,
                appearance = appearance,
                onChange = onShownIn,
                modifier = Modifier.padding(start = 16.dp).fillMaxWidth().settingsAnchor(showsInAnchor),
                enabled = !readOnly,
                keepOne = false
            )
        }
    }
}

/** The ≡ handle's touch target: the toolkit's line keeps the room so the emblems line up. */
private val HANDLE_WIDTH = 48.dp
