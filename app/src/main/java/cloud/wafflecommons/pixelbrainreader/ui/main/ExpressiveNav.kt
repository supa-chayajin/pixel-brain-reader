@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.ui.components.badgeShapeFor
import cloud.wafflecommons.pixelbrainreader.ui.components.toneFor
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The app's navigation (13.2, the owner's pick of 2026-11-16: "shape-morphing tabs", with three
 * extras). The floating bar on a phone and a portrait tablet, the rail on a wide window; the tabs,
 * their order and the four-tab fold are unchanged (settled decision #17, the owner's order).
 *
 *  - The open tab sits on its page's emblem: the same expressive shape and tone as the page header's
 *    (`badgeShapeFor` / `toneFor` of the tab's name), its icon filled while the others are outlined.
 *    Between tabs the emblem slides, stretching like jelly with the spring's speed, then pops into
 *    the new tab's shape and colour as it arrives.
 *  - Daily is a sun shape that turns while Daily is open.
 *  - A running game session puts a pulsing dot on Games.
 *  - The rail widens to show the names beside the icons (a menu button at its top, kept on the
 *    device), and the phone's bar slides away while a page scrolls down ([NavBarHideOnScroll]).
 *
 * Every colour is a role of the device's dynamic scheme.
 */

/** One regular tab of the bar or the rail. [iconOff] is its outlined icon, while it isn't open. */
internal data class NavDest(
    val route: String,
    val icon: ImageVector,
    val iconOff: ImageVector,
    val label: String,
    val selected: Boolean,
    /** Games while a session runs: the live dot. */
    val live: Boolean = false
)

/**
 * The regular tabs (all but Daily) in the user's custom order; a destination absent from the saved
 * order (one added in a later app version) is appended, so nothing ever disappears from the bar.
 */
internal fun navDestinations(
    currentRoute: String?,
    isViewingDailyNote: Boolean,
    order: List<String>,
    hidden: Set<String> = emptySet(),
    gameRunning: Boolean = false
): List<NavDest> =
    inNavOrder(
        order,
        listOfNotNull(
            NavDest(Screen.Home, Icons.Rounded.Dashboard, Icons.Outlined.Dashboard, "Repo", currentRoute == Screen.Home && !isViewingDailyNote),
            NavDest("habits", Icons.Rounded.DateRange, Icons.Outlined.DateRange, "Habits", currentRoute == "habits"),
            NavDest(Screen.HomeOS, Icons.Rounded.CleaningServices, Icons.Outlined.CleaningServices, "Chores", currentRoute == Screen.HomeOS),
            // No AI on a read-only device (DeviceRole).
            NavDest(Screen.Chat, Icons.Rounded.Psychology, Icons.Outlined.Psychology, "Chat", currentRoute == Screen.Chat)
                .takeUnless { VaultWriteGate.readOnly },
            NavDest(Screen.MoodTracker, Icons.Rounded.Mood, Icons.Outlined.Mood, "Mood", currentRoute == Screen.MoodTracker),
            NavDest(Screen.Stats, Icons.Rounded.Star, Icons.Rounded.StarOutline, "Stats", currentRoute == Screen.Stats),
            // Also lit on a project page opened directly (the Projects widget, a deep link).
            NavDest(
                V11Routes.PROJECTS,
                Icons.Rounded.RocketLaunch,
                Icons.Outlined.RocketLaunch,
                "Projects",
                currentRoute == V11Routes.PROJECTS || currentRoute == V11Routes.PROJECT
            ),
            // 12.17 (RFC-009): lit on a game's page too.
            NavDest(
                GamesRoutes.GAMES,
                Icons.Rounded.SportsEsports,
                Icons.Outlined.SportsEsports,
                "Games",
                currentRoute == GamesRoutes.GAMES || currentRoute == GamesRoutes.GAME,
                live = gameRunning
            )
        )
    ) { it.route }.filterNot { it.route in hidden }

/** How many tabs the floating bar shows below 600 dp (the folded phone). */
private const val FOLDED_TABS = 4

/** Corner radius of the nav surfaces; the tabs sit [NavInnerPad] inside them. */
private val NavBarRadius = 28.dp
private val NavInnerPad = 6.dp
private val NavShadowElevation = 6.dp

/**
 * The bar: an emblem [BarEmblem] wide, [BarEmblemTop] below the tab's top, the name under it. The
 * icon stays about half the emblem (the page headers' ratio): the owner's review found a bigger one
 * looked about to pop out of its shape.
 */
private val BarEmblem = 34.dp
private val BarEmblemTop = 0.dp
private val BarIcon = 18.dp

/** The rail: [RailWidth] wide, [RailWidthExpanded] with the names beside the icons. */
private val RailWidth = 76.dp
private val RailWidthExpanded = 204.dp
private val RailItemHeight = 60.dp
private val RailEmblem = 40.dp
private val RailEmblemTop = 2.dp
private val RailIcon = 20.dp
private val RailExpandedStart = 10.dp

/**
 * The floating navigation bar of a phone or a portrait tablet: a rounded group of the regular tabs
 * and, apart, the emphasized Daily button. Folded shows the first four tabs of the user's order;
 * unfolded reveals all.
 */
@Composable
internal fun ExpressiveNavBar(
    currentRoute: String?,
    isViewingDailyNote: Boolean,
    isLargeScreen: Boolean,
    order: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    gameRunning: Boolean = false
) {
    val shadowColor = navShadowColor()
    // The first four of the user's order = the folded set (four since 12.17, owner's D9: Games made
    // eight tabs, and three left too many out of reach). Unfolded reveals the rest.
    val ordered = navDestinations(currentRoute, isViewingDailyNote, order, hidden, gameRunning)
    val regular = if (isLargeScreen) ordered else ordered.take(FOLDED_TABS)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(NavBarRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .weight(1f)
                .height(ExpressiveNavBarHeight)
                // Theme-coloured (primary) elevation shadow: the default black one turns into a white
                // halo under a device force-dark override.
                .shadow(
                    elevation = NavShadowElevation,
                    shape = RoundedCornerShape(NavBarRadius),
                    spotColor = shadowColor,
                    ambientColor = shadowColor
                )
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(NavInnerPad)) {
                val count = regular.size.coerceAtLeast(1)
                val itemWidthPx = constraints.maxWidth.toFloat() / count
                val centerY = with(LocalDensity.current) { (BarEmblemTop + BarEmblem / 2).toPx() }
                SlidingEmblem(
                    selectedIndex = regular.indexOfFirst { it.selected },
                    keys = regular.map { it.label },
                    center = { i -> Offset((i + 0.5f) * itemWidthPx, centerY) },
                    vertical = false,
                    size = BarEmblem,
                    stepPx = itemWidthPx
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    regular.forEach { item ->
                        NavPiece(
                            item = item,
                            onClick = { if (!item.selected) onSelect(item.route) },
                            emblem = BarEmblem,
                            iconSize = BarIcon,
                            emblemTop = BarEmblemTop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }

        DailyNavButton(
            selected = currentRoute == Screen.DailyNote,
            onSelect = { onSelect(Screen.DailyNote) },
            shadowColor = shadowColor
        )
    }
}

/**
 * The navigation of a wide window (a tablet in landscape, an unfolded Fold): the same tab group and
 * Daily button stood up along the left edge, every tab in the user's order. The menu button at its
 * top widens it to show the names beside the icons ([expanded], kept on the device). The group
 * scrolls when the window is too short for it.
 */
@Composable
internal fun ExpressiveNavRail(
    currentRoute: String?,
    isViewingDailyNote: Boolean,
    order: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    gameRunning: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {}
) {
    val shadowColor = navShadowColor()
    val regular = navDestinations(currentRoute, isViewingDailyNote, order, hidden, gameRunning)
    val width by animateDpAsState(
        targetValue = if (expanded) RailWidthExpanded else RailWidth,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "railWidth"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Centred over the icons in both widths ((76 − 48) / 2).
            IconButton(
                onClick = onToggleExpanded,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.padding(start = 14.dp)
            ) {
                Icon(
                    if (expanded) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                    contentDescription = if (expanded) "Hide the tab names" else "Show the tab names"
                )
            }

            DailyNavButton(
                selected = currentRoute == Screen.DailyNote,
                onSelect = { onSelect(Screen.DailyNote) },
                shadowColor = shadowColor,
                modifier = Modifier.width(width),
                horizontalPadding = if (expanded) 16.dp else 0.dp,
                beside = expanded
            )

            Surface(
                shape = RoundedCornerShape(NavBarRadius),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .width(width)
                    .shadow(
                        elevation = NavShadowElevation,
                        shape = RoundedCornerShape(NavBarRadius),
                        spotColor = shadowColor,
                        ambientColor = shadowColor
                    )
            ) {
                val density = LocalDensity.current
                val itemPx = with(density) { RailItemHeight.toPx() }
                val centerX = with(density) {
                    if (expanded) (RailExpandedStart + RailEmblem / 2).toPx() else ((RailWidth - NavInnerPad * 2) / 2).toPx()
                }
                val centerInItem = with(density) {
                    if (expanded) (RailItemHeight / 2).toPx() else (RailEmblemTop + RailEmblem / 2).toPx()
                }
                Box(modifier = Modifier.padding(NavInnerPad)) {
                    SlidingEmblem(
                        selectedIndex = regular.indexOfFirst { it.selected },
                        keys = regular.map { it.label },
                        center = { i -> Offset(centerX, i * itemPx + centerInItem) },
                        vertical = true,
                        size = RailEmblem,
                        stepPx = itemPx
                    )
                    Column {
                        regular.forEach { item ->
                            NavPiece(
                                item = item,
                                onClick = { if (!item.selected) onSelect(item.route) },
                                emblem = RailEmblem,
                                iconSize = RailIcon,
                                emblemTop = RailEmblemTop,
                                beside = expanded,
                                modifier = Modifier.fillMaxWidth().height(RailItemHeight)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The open tab's emblem: the expressive shape and tone of its page's header emblem, sliding between
 * the tabs. On the way it stretches like jelly with the spring's speed; as it arrives it pops into
 * the new tab's shape and colour. It fades out while Daily (apart from the group) is open, parked
 * where it was so it slides back from there.
 */
@Composable
private fun SlidingEmblem(
    selectedIndex: Int,
    keys: List<String>,
    center: (Int) -> Offset,
    vertical: Boolean,
    size: Dp,
    stepPx: Float
) {
    if (keys.isEmpty()) return
    var parked by remember { mutableIntStateOf(selectedIndex.coerceAtLeast(0)) }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) parked = selectedIndex }
    val index = parked.coerceIn(0, keys.lastIndex)
    val target = center(index)
    val along = if (vertical) target.y else target.x
    val across by animateFloatAsState(
        targetValue = if (vertical) target.x else target.y,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "emblemAcross"
    )
    val position = remember { Animatable(along) }
    val pop = remember { Animatable(1f) }
    var shownKey by remember { mutableStateOf(keys[index]) }
    LaunchedEffect(index, along, keys) {
        val key = keys[index]
        coroutineScope {
            launch { position.animateTo(along, spring(dampingRatio = 0.62f, stiffness = 420f)) }
            // The new shape arrives with the emblem, not when it leaves.
            snapshotFlow { abs(position.value - along) }.first { it < stepPx * 0.3f }
            if (key != shownKey) {
                shownKey = key
                pop.snapTo(0.7f)
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 520f))
            }
        }
    }
    val visible by animateFloatAsState(
        targetValue = if (selectedIndex >= 0) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "emblemAlpha"
    )
    val color by animateColorAsState(
        targetValue = toneFor(shownKey).container,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "emblemColor"
    )
    val shape = badgeShapeFor(shownKey).toShape()
    val half = with(LocalDensity.current) { size.toPx() / 2f }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val stretch = (abs(position.velocity) / (stepPx * 14f)).coerceIn(0f, 0.55f)
                val alongScale = (1f + stretch) * pop.value
                val acrossScale = (1f - stretch * 0.35f) * pop.value
                translationX = (if (vertical) across else position.value) - half
                translationY = (if (vertical) position.value else across) - half
                scaleX = if (vertical) acrossScale else alongScale
                scaleY = if (vertical) alongScale else acrossScale
                alpha = visible
            }
            .clip(shape)
            .background(color)
    )
}

/** One tab: its icon in the emblem's slot, its name under it, or beside it in the widened rail. */
@Composable
private fun NavPiece(
    item: NavDest,
    onClick: () -> Unit,
    emblem: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    emblemTop: Dp = 0.dp,
    beside: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val tint by animateColorAsState(
        targetValue = if (item.selected) toneFor(item.label).content else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (item.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navLabel"
    )
    // A short bounce as its tab opens, resting at its own size so the emblem keeps its room around it.
    val iconScale = remember { Animatable(1f) }
    LaunchedEffect(item.selected) {
        if (item.selected) {
            iconScale.snapTo(0.82f)
            iconScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
        } else {
            iconScale.snapTo(1f)
        }
    }
    val clickable = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
    ) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    val icon: @Composable () -> Unit = {
        Box(modifier = Modifier.size(emblem), contentAlignment = Alignment.Center) {
            Icon(
                if (item.selected) item.icon else item.iconOff,
                contentDescription = if (item.live) "${item.label}, a game session is running" else item.label,
                tint = tint,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value }
            )
            if (item.live) LiveDot(Modifier.align(Alignment.TopEnd))
        }
    }
    val name: @Composable () -> Unit = {
        Text(
            item.label,
            style = if (beside) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            fontWeight = if (item.selected) FontWeight.Bold else null,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
    if (beside) {
        Row(
            modifier = modifier.then(clickable).padding(start = RailExpandedStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            name()
        }
    } else {
        Column(modifier = modifier.then(clickable), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(emblemTop))
            icon()
            Spacer(Modifier.height(2.dp))
            name()
        }
    }
}

/** A game session is running: a small pulsing dot on the Games tab, seen from every page. */
@Composable
private fun LiveDot(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "live")
    val halo by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "liveHalo"
    )
    val color = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier.size(10.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = halo
                    scaleY = halo
                    alpha = ((1.6f - halo) * 0.6f).coerceIn(0f, 0.6f)
                }
                .background(color, CircleShape)
        )
        Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
    }
}

/**
 * The floating nav surfaces' elevation shadow is tinted with the primary accent. On a dark surface
 * that bright tint reads as a glowing halo, but a shadow dimmed to black vanishes against the dark
 * page: on dark themes it stays coloured, its brightness dialled partway toward black. Derived from
 * the resolved surface luminance so it tracks the actual theme.
 */
@Composable
private fun navShadowColor(): Color {
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDarkSurface) lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.4f)
        else MaterialTheme.colorScheme.primary
}

/**
 * The emphasized Daily button, apart from the tab group: a sun shape behind its icon, turning
 * while Daily is open and resting otherwise, an accent border and a small pop when it opens.
 * [horizontalPadding] gives it its width in the bar; the rail gives it a width instead, and
 * [beside] puts the name beside the sun in the widened rail.
 */
@Composable
private fun DailyNavButton(
    selected: Boolean,
    onSelect: () -> Unit,
    shadowColor: Color,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 22.dp,
    beside: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val sunColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else accent.copy(alpha = 0.14f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dailySun"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else accent.copy(alpha = 0.75f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dailyIcon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) accent else accent.copy(alpha = 0.6f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dailyLabel"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 0.9f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "dailyBorder"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dailyScale"
    )
    // The sun turns only while Daily is open (no frames spent otherwise) and rests where it stopped.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            while (true) {
                angle.snapTo(angle.value % 360f)
                angle.animateTo(angle.value + 360f, tween(20_000, easing = LinearEasing))
            }
        }
    }
    val sunShape = MaterialShapes.Sunny.toShape()
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(ExpressiveNavBarHeight)
            .shadow(
                elevation = NavShadowElevation,
                shape = RoundedCornerShape(NavBarRadius),
                spotColor = shadowColor,
                ambientColor = shadowColor
            )
            .clip(RoundedCornerShape(NavBarRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(BorderStroke(1.5.dp, accent.copy(alpha = borderAlpha)), RoundedCornerShape(NavBarRadius))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (!selected) onSelect()
            }
            .padding(horizontal = horizontalPadding),
        contentAlignment = if (beside) Alignment.CenterStart else Alignment.Center
    ) {
        val sun: @Composable () -> Unit = {
            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .graphicsLayer { rotationZ = angle.value }
                        .clip(sunShape)
                        .background(sunColor)
                )
                Icon(Icons.Rounded.AutoAwesome, contentDescription = "Daily", tint = iconTint, modifier = Modifier.size(18.dp))
            }
        }
        if (beside) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                sun()
                Spacer(Modifier.width(12.dp))
                Text("Daily", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = labelColor, maxLines = 1, softWrap = false)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                sun()
                Spacer(Modifier.height(2.dp))
                Text("Daily", style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
            }
        }
    }
}

/**
 * Hides the phone's floating bar while a page scrolls down and shows it again as it scrolls up
 * (13.2, the owner's extra), once the content has moved [thresholdPx] one way; a change of
 * direction starts the count again. [show] brings it back (a new page).
 */
internal class NavBarHideOnScroll(private val thresholdPx: Float) : NestedScrollConnection {
    var hidden by mutableStateOf(false)
        private set
    private var travel = 0f

    fun show() {
        hidden = false
        travel = 0f
    }

    /** [dy]: how far the content moved, negative while it scrolls down (the finger goes up). */
    fun onMoved(dy: Float) {
        if (dy == 0f) return
        if (travel != 0f && (dy < 0f) != (travel < 0f)) travel = 0f
        travel += dy
        if (travel <= -thresholdPx) hidden = true else if (travel >= thresholdPx) hidden = false
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        onMoved(consumed.y)
        return Offset.Zero
    }
}
