package cloud.wafflecommons.pixelbrainreader.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Shared visual language for the whole widget suite. Every widget is built from these primitives so
 * the seven surfaces read as one system (same corner radii, same tile shape, same accent set) in
 * both light and dark, on the wallpaper-derived Material You palette.
 */
object WidgetTokens {
    // Non-adaptive brand accents, mirrored from the app's SemanticPalette so widgets match in-app.
    val XpGold = ColorProvider(Color(0xFFFFD700))
    val StreakOrange = ColorProvider(Color(0xFFFF9800))
    val Success = ColorProvider(Color(0xFF4CAF50))
    val HeartRed = ColorProvider(Color(0xFFFF5252))
    val SleepPurple = ColorProvider(Color(0xFF7E6BD6))

    val CardRadius = 24.dp
    val TileRadius = 18.dp
    val Pad = 16.dp
}

/** Rounded, themed root container every widget wraps its content in. */
@Composable
fun WidgetSurface(
    modifier: GlanceModifier = GlanceModifier,
    onClick: Action? = null,
    content: @Composable () -> Unit
) {
    var m = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.surface)
        .cornerRadius(WidgetTokens.CardRadius)
        .padding(WidgetTokens.Pad)
    if (onClick != null) m = m.clickable(onClick)
    Box(modifier = modifier.then(m)) { content() }
}

/** Title row: leading emoji + title, plus an optional trailing icon-button (refresh, open, …). */
@Composable
fun WidgetHeader(
    emoji: String,
    title: String,
    onTitleClick: Action? = null,
    trailingEmoji: String? = null,
    onTrailingClick: Action? = null,
    subtitle: String? = null
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var titleMod = GlanceModifier.defaultWeight()
        if (onTitleClick != null) titleMod = titleMod.clickable(onTitleClick)
        Row(modifier = titleMod, verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = TextStyle(fontSize = 16.sp))
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    title,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface
                    )
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }
        }
        if (trailingEmoji != null && onTrailingClick != null) {
            IconChip(trailingEmoji, onTrailingClick)
        }
    }
}

/** A small circular tap target holding a single emoji/glyph. */
@Composable
fun IconChip(emoji: String, onClick: Action, tint: ColorProvider? = null) {
    Box(
        modifier = GlanceModifier
            .size(32.dp)
            .background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(16.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            emoji,
            style = TextStyle(fontSize = 15.sp, color = tint ?: GlanceTheme.colors.onSecondaryContainer)
        )
    }
}

/** Compact stat tile — big value on top, tiny label under, filled container. */
@Composable
fun StatTile(
    emoji: String,
    value: String,
    label: String,
    modifier: GlanceModifier = GlanceModifier,
    onClick: Action? = null
) {
    var m = modifier
        .background(GlanceTheme.colors.secondaryContainer)
        .cornerRadius(WidgetTokens.TileRadius)
        .padding(vertical = 7.dp, horizontal = 10.dp)
    if (onClick != null) m = m.clickable(onClick)
    Column(modifier = m, horizontalAlignment = Alignment.Start) {
        Text(emoji, style = TextStyle(fontSize = 12.sp))
        Text(
            value,
            maxLines = 1,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onSecondaryContainer
            )
        )
        Text(
            label,
            maxLines = 1,
            style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurfaceVariant)
        )
    }
}

/** Rounded full pill: emoji + bold value, used in tight rows. */
@Composable
fun StatPill(emoji: String, value: String, modifier: GlanceModifier = GlanceModifier) {
    Row(
        modifier = modifier
            .background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(24.dp)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, style = TextStyle(fontSize = 14.sp))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            value,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onSecondaryContainer
            )
        )
    }
}

/** A labeled determinate progress bar with a trailing count (e.g. "3/5"). */
@Composable
fun ProgressRow(
    label: String,
    fraction: Float,
    trailing: String,
    color: ColorProvider = GlanceTheme.colors.primary
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
            )
            Text(
                trailing,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface
                )
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        LinearProgressIndicator(
            // coerceIn passes NaN through, so guard it explicitly before it reaches the indicator.
            progress = (if (fraction.isNaN()) 0f else fraction).coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(4.dp),
            color = color,
            backgroundColor = GlanceTheme.colors.surfaceVariant
        )
    }
}

/** Big square action button (emoji over label) used by the Quick Actions widget. */
@Composable
fun ActionButton(
    emoji: String,
    label: String,
    action: Action,
    modifier: GlanceModifier = GlanceModifier,
    container: ColorProvider? = null
) {
    Column(
        modifier = modifier
            .background(container ?: GlanceTheme.colors.primaryContainer)
            .cornerRadius(WidgetTokens.TileRadius)
            .padding(vertical = 9.dp, horizontal = 6.dp)
            .clickable(action),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, style = TextStyle(fontSize = 20.sp))
        Spacer(GlanceModifier.height(4.dp))
        Text(
            label,
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = GlanceTheme.colors.onPrimaryContainer
            )
        )
    }
}

/** Centered empty/placeholder state ("All done!", "No data"). */
@Composable
fun WidgetEmpty(emoji: String, message: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, style = TextStyle(fontSize = 28.sp))
        Spacer(GlanceModifier.height(6.dp))
        Text(
            message,
            style = TextStyle(
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = GlanceTheme.colors.onSurfaceVariant
            )
        )
    }
}
