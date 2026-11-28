@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.components.ConnectedButtonRow
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.components.ExpressiveBadge
import cloud.wafflecommons.pixelbrainreader.ui.components.connectedShapes
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.components.switchThumb
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth

/*
 * The Settings kit (13.4, S-a): one frame and one row for every Settings page.
 *
 * S9, the truncation the owner saw: the 12.x sections clipped their whole column to the 32 dp
 * extra-large shape, so a loose text or a button with no inner margin lost the corners of its
 * first and last lines. From 13.4 no group clips: each entry is a row drawn in its own segment
 * shape (28 dp outside, 6 dp inside) with an inner margin wider than the curve, and every loose
 * text or button is a row of its own (SettingsNote, a row's `below`).
 */

/** The inner margin of every entry (S9): its text starts inside the segment's 28 dp curve. */
val SettingsRowPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

/** A row's place in its group: its segment shape follows (large outer corners, small inner ones). */
@Immutable
data class SegmentPosition(val index: Int, val count: Int) {
    val shape: Shape get() = segmentShape(index, count)
}

/** The place of the row being drawn in its [SettingsGroup]; a row on its own is a whole segment. */
val LocalSegmentPosition = staticCompositionLocalOf { SegmentPosition(0, 1) }

/** A group's rows (S9): each drawn with its own segment shape; the group itself never clips. */
class SettingsRows internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** Rows as one segmented group: a Column with 2 dp between, each row given its [SegmentPosition]. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, build: SettingsRows.() -> Unit) {
    val rows = SettingsRows().apply(build).rows
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            CompositionLocalProvider(LocalSegmentPosition provides SegmentPosition(index, rows.size)) { row() }
        }
    }
}

/**
 * A home topic (S1): its emblem (ExpressiveBadge 34 dp) and title (titleLargeEmphasized), then its
 * group. Not named SettingsSection: the 12.x composable of that name lives in SettingsScreen.kt until
 * settings_shell rewrites it, and two overloads taking a trailing lambda would be ambiguous there.
 */
@Composable
fun SettingsTopicSection(title: String, icon: ImageVector, modifier: Modifier = Modifier, build: SettingsRows.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Merged, so the title is the heading TalkBack's heading navigation stops at.
            modifier = Modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { heading() }
        ) {
            ExpressiveBadge(icon = icon, key = title, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup(build = build)
    }
}

/**
 * A titled group inside a page (a page with several groups: Leave & key dates, Moods, RPG rules):
 * a small heading in the primary tone, then the group.
 */
@Composable
internal fun SettingsPageSection(title: String, modifier: Modifier = Modifier, build: SettingsRows.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSubhead(title)
        Spacer(Modifier.height(8.dp))
        SettingsGroup(build = build)
    }
}

/** A group's heading: what the rows under it are about. In a lazy page, [modifier] gives it the room above it. */
@Composable
internal fun SettingsSubhead(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp).semantics { heading() }
    )
}

/**
 * One entry: a Surface in its segment shape, surfaceContainerHigh, [SettingsRowPadding]; an
 * ExpressiveBadge (36 dp, key [iconKey]); the title (bodyLarge) and one-line [description]
 * (bodyMedium, onSurfaceVariant); [trailing] at the end; [below] under the text for buttons and
 * progress. [onClick] makes it a link (haptic TextHandleMove). [anchor]: see [settingsAnchor].
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconKey: String = title,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    anchor: String? = null,
    shape: Shape = LocalSegmentPosition.current.shape,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val interaction = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    } else {
        Modifier
    }
    SettingsRowFrame(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        iconKey = iconKey,
        enabled = enabled,
        anchor = anchor,
        shape = shape,
        interaction = interaction,
        trailing = trailing,
        below = below
    )
}

/** A switch entry: the whole row toggles (Modifier.toggleable, Role.Switch); Switch(thumbContent = switchThumb(checked)). */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    anchor: String? = null,
    shape: Shape = LocalSegmentPosition.current.shape
) {
    val haptic = LocalHapticFeedback.current
    SettingsRowFrame(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        iconKey = title,
        enabled = enabled,
        anchor = anchor,
        shape = shape,
        interaction = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(it)
        },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled, thumbContent = switchThumb(checked)) },
        below = null
    )
}

/**
 * A ticked entry (a folder that counts as work, a project the Personal mode shows): the whole row
 * toggles (Role.Checkbox), the box at its end.
 */
@Composable
internal fun SettingsCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    anchor: String? = null,
    shape: Shape = LocalSegmentPosition.current.shape
) {
    val haptic = LocalHapticFeedback.current
    SettingsRowFrame(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        iconKey = title,
        enabled = enabled,
        anchor = anchor,
        shape = shape,
        interaction = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(it)
        },
        trailing = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        below = null
    )
}

/** A 2- to 4-way choice: the title, then a ConnectedButtonRow of ToggleButtons ([connectedShapes]). */
@Composable
fun SettingsChoiceRow(
    title: String,
    choices: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    anchor: String? = null,
    shape: Shape = LocalSegmentPosition.current.shape
) {
    val haptic = LocalHapticFeedback.current
    SettingsRowFrame(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        iconKey = title,
        enabled = enabled,
        anchor = anchor,
        shape = shape,
        interaction = Modifier,
        trailing = null,
        below = {
            ConnectedButtonRow(modifier = Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, label ->
                    ToggleButton(
                        checked = index == selected,
                        onCheckedChange = {
                            if (index != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            }
                        },
                        enabled = enabled,
                        shapes = connectedShapes(index, choices.size),
                        // Four choices share a phone's width: the labels keep their room.
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    )
}

/** Text that belongs inside a group (a read-only line, a lead): a row of its own, never loose (S9). */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier, shape: Shape = LocalSegmentPosition.current.shape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(SettingsRowPadding)
        )
    }
}

/**
 * The frame every row shares: its segment shape (the Surface clips what it holds, the ripple
 * included), the inner margin, the badge, the texts, then [trailing] and [below]. [interaction]
 * (a click, a toggle) covers the whole row, margin included.
 */
@Composable
private fun SettingsRowFrame(
    title: String,
    modifier: Modifier,
    description: String?,
    icon: ImageVector?,
    iconKey: String,
    enabled: Boolean,
    anchor: String?,
    shape: Shape,
    interaction: Modifier,
    trailing: (@Composable () -> Unit)?,
    below: (@Composable ColumnScope.() -> Unit)?
) {
    val dim = if (enabled) 1f else DISABLED_ALPHA
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .anchorLight(anchor, shape, behind = true)
                .then(interaction)
                .padding(SettingsRowPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    ExpressiveBadge(icon = icon, key = iconKey, size = 36.dp, modifier = Modifier.alpha(dim))
                    Spacer(Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                    )
                    if (description != null) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim)
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
            if (below != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = below
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.5f

/**
 * A page's frame: the FormScaffold look (the centred CortexTopAppBar with [page]'s title, icon and
 * pill, the back arrow, a column at ContentMaxWidth that clears the floating bar, the snackbar
 * above it), with the search's focus ([LocalSettingsFocus]). Its scroll is handed down
 * ([LocalSettingsScroll]) so a list dragged near the page's edge scrolls it.
 */
@Composable
fun SettingsPageScaffold(
    page: SettingsPage,
    onBack: () -> Unit,
    appearance: ModeAppearance = ModeAppearance(),
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val focus = remember { SettingsFocus.take(page.route) }
    val scroll = rememberScrollState()
    Scaffold(
        // 13.2: the centred bar, the page's emblem, its section as the pill, a back arrow.
        topBar = {
            CortexTopAppBar(
                title = page.titleFor(appearance),
                subtitle = page.subtitle,
                icon = page.icon,
                navigationIcon = {
                    CortexIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it, modifier = Modifier.padding(bottom = NavBarClearance)) } }
    ) { padding ->
        CompositionLocalProvider(LocalSettingsFocus provides focus, LocalSettingsScroll provides scroll) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // One centred column on a wide window (settled decision #17), the whole width scrolling.
                    .centeredMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

/**
 * The same frame over a LazyColumn (centeredMaxWidth, 16 dp sides, NavBarClearance at the bottom).
 * Its items sit 2 dp apart, a segmented list's gap: a heading gives itself the room above it.
 */
@Composable
fun SettingsLazyPageScaffold(
    page: SettingsPage,
    onBack: () -> Unit,
    appearance: ModeAppearance = ModeAppearance(),
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val focus = remember { SettingsFocus.take(page.route) }
    Scaffold(
        // 13.2: the centred bar, the page's emblem, its section as the pill, a back arrow.
        topBar = {
            CortexTopAppBar(
                title = page.titleFor(appearance),
                subtitle = page.subtitle,
                icon = page.icon,
                navigationIcon = {
                    CortexIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it, modifier = Modifier.padding(bottom = NavBarClearance)) } },
        floatingActionButton = floatingActionButton
    ) { padding ->
        CompositionLocalProvider(LocalSettingsFocus provides focus) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize().centeredMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content
            )
        }
    }
}

/** The anchor the search opened this page at (S6), provided by the page frames. */
val LocalSettingsFocus = staticCompositionLocalOf<String?> { null }

/** The page frame's scroll ([SettingsPageScaffold]), for a drag that scrolls the page near its edge. */
internal val LocalSettingsScroll = staticCompositionLocalOf<ScrollState?> { null }

/**
 * The page's scroll as a drag's `scrollBy`, or null outside a [SettingsPageScaffold]: call it in the
 * frame's content (where [LocalSettingsScroll] is provided), never before the frame.
 */
@Composable
internal fun rememberSettingsScrollBy(): (suspend (Float) -> Float)? {
    val scroll = LocalSettingsScroll.current ?: return null
    return remember<suspend (Float) -> Float>(scroll) { { delta -> scroll.scrollBy(delta) } }
}

/**
 * When [anchor] is the page's focus: bring the row into view once (BringIntoViewRequester) and light
 * it (secondaryContainer fading back over 1.6 s), over the row in its segment shape. The kit's rows
 * apply it themselves; a custom row (a Reminders card, the Navigation list) calls it.
 */
fun Modifier.settingsAnchor(anchor: String?): Modifier = anchorLight(anchor, shape = null, behind = false)

/**
 * The anchor's light. [behind]: under the row's content, inside its Surface (the kit's rows);
 * otherwise over it, drawn in [shape] (the row's segment shape when null), translucent.
 */
private fun Modifier.anchorLight(anchor: String?, shape: Shape?, behind: Boolean): Modifier {
    if (anchor == null) return this
    return this.composed {
        if (LocalSettingsFocus.current != anchor) return@composed Modifier
        val requester = remember { BringIntoViewRequester() }
        val glow = remember { Animatable(1f) }
        val color = MaterialTheme.colorScheme.secondaryContainer
        val outlineShape = shape ?: LocalSegmentPosition.current.shape
        LaunchedEffect(Unit) {
            // Once the page has been laid out, so the row has a place to be brought to.
            withFrameNanos { }
            requester.bringIntoView()
            glow.animateTo(0f, tween(durationMillis = ANCHOR_LIGHT_MS, easing = LinearOutSlowInEasing))
        }
        val light = Modifier.bringIntoViewRequester(requester)
        if (behind) {
            light.drawBehind { if (glow.value > 0f) drawRect(color, alpha = glow.value) }
        } else {
            light.drawWithContent {
                drawContent()
                if (glow.value > 0f) {
                    drawOutline(outlineShape.createOutline(size, layoutDirection, this), color, alpha = glow.value * OVERLAY_ALPHA)
                }
            }
        }
    }
}

private const val ANCHOR_LIGHT_MS = 1_600
private const val OVERLAY_ALPHA = 0.6f

/** The search's one-shot hand-off to the page it opens (S6), main thread only. Pure. */
object SettingsFocus {

    private var pending: Pair<String, String>? = null

    /** The next page opened on [route] lights its row [anchor]. */
    fun request(route: String, anchor: String) {
        pending = route to anchor
    }

    /** The anchor requested for [route], once; another route's request is dropped. */
    fun take(route: String): String? {
        val request = pending
        pending = null
        return request?.takeIf { it.first == route }?.second
    }
}
