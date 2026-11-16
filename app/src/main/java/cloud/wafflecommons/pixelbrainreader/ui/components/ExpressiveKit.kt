package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer

/*
 * The app's Material 3 Expressive kit (13.2, the owner's call: "fully Material 3 Expressive",
 * colourful within the device's own colours). Every colour here is a role of the dynamic
 * ColorScheme, never a fixed one, so the app follows the wallpaper and the system's dark theme.
 */

/** A switch's thumb: a check while on (the expressive switch), nothing while off. */
fun switchThumb(checked: Boolean): (@Composable () -> Unit)? =
    if (checked) {
        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
    } else {
        null
    }

/** A connected button group's shapes for the button at [index] of [count] (the segmented row's successor). */
@Composable
fun connectedShapes(index: Int, count: Int): ToggleButtonShapes = when {
    count <= 1 -> ToggleButtonDefaults.shapes()
    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

/** A connected button group: its toggle buttons side by side, [connectedShapes] each. */
@Composable
fun ConnectedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** A container role and the content role that reads on it. */
data class Tone(val container: Color, val content: Color)

/** The three accent tones of the device's scheme, in turn: primary, secondary, tertiary. */
fun ColorScheme.accentTones(): List<Tone> = listOf(
    Tone(primaryContainer, onPrimaryContainer),
    Tone(secondaryContainer, onSecondaryContainer),
    Tone(tertiaryContainer, onTertiaryContainer)
)

/** A stable tone for [key]: the same card keeps its colour, neighbours differ. */
@Composable
fun toneFor(key: String): Tone {
    val tones = MaterialTheme.colorScheme.accentTones()
    return tones[key.hashCode().absoluteValue % tones.size]
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BADGE_SHAPES = listOf(
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Sunny
)

/** The expressive shape of [key]'s emblem: a page header's, and its tab's in the navigation (13.2). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun badgeShapeFor(key: String): androidx.graphics.shapes.RoundedPolygon =
    BADGE_SHAPES[(key.hashCode() / 3).absoluteValue % BADGE_SHAPES.size]

/**
 * An icon on an expressive shape (a cookie, a clover, a sunny burst), in [key]'s tone: the badge of
 * a card's title. The shape and the tone follow [key], so a card always looks the same.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveBadge(icon: ImageVector, key: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tone: Tone = toneFor(key)) {
    val shape = badgeShapeFor(key).toShape()
    Box(
        modifier = modifier.size(size).clip(shape).background(tone.container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tone.content, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A Daily block's header (13.2): its emblem on an expressive shape in the block's tone, then the
 * emphasized title. The blocks keep the owner's order; only their look follows Material 3 Expressive.
 */
@Composable
fun DailyBlockHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ExpressiveBadge(icon = icon, key = title, size = 36.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(width = 12.dp, height = 0.dp))
        androidx.compose.material3.Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** An Android 16 list: [content]'s rows as one segmented group (extra-large outer corners, 2 dp gaps). */
@Composable
fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.clip(MaterialTheme.shapes.extraLarge),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** The shape of row [index] of [count] in a segmented list: large outer corners, small inner ones. */
fun segmentShape(index: Int, count: Int, outer: Dp = 28.dp, inner: Dp = 6.dp): androidx.compose.ui.graphics.Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return androidx.compose.foundation.shape.RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * A slowly turning expressive shape behind [content] (13.2): the weather's illustration, the day's
 * mood. The shape and its colour come from Material 3 Expressive and the device's scheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveHalo(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    shape: androidx.graphics.shapes.RoundedPolygon = MaterialShapes.Cookie12Sided,
    content: @Composable () -> Unit
) {
    val turning = androidx.compose.animation.core.rememberInfiniteTransition(label = "halo")
    val angle by turning.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(durationMillis = 30_000, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "angle"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = angle }
                .clip(shape.toShape())
                .background(color)
        )
        content()
    }
}

