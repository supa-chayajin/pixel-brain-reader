package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Life Stats' one visual system (12.21, the owner's D7): every card on the same surface and shape,
 * headed by its icon on a soft cookie of the card's accent; big numbers in tiles, small charts.
 * Material 3 Expressive, the cards in their usual order.
 */
@Composable
internal fun StatsCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, accent)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.let { LabelChip(it) }
                action?.invoke()
            }
            content()
        }
    }
}

/** A card's mark: its icon on a soft cookie of its accent, kept readable on the card. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IconBadge(icon: ImageVector, accent: Color, size: Dp = 40.dp) {
    val readable = readableOn(accent, MaterialTheme.colorScheme.surfaceContainer)
    Box(
        modifier = Modifier.size(size).clip(MaterialShapes.Cookie4Sided.toShape()).background(readable.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = readable, modifier = Modifier.size(size * 0.55f))
    }
}

/** A small rounded label: a period, a count, N. */
@Composable
internal fun LabelChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** A tile inside a card: its icon, a big value, the label, and an optional note in the accent. */
@Composable
internal fun StatsTile(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, note: String? = null) {
    val readable = readableOn(accent, MaterialTheme.colorScheme.surfaceContainerHigh)
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = readable, modifier = Modifier.size(22.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = readable) }
        }
    }
}

/**
 * Where [rho] sits between −1 and +1 (D8): the track with its middle mark, a fill from the middle
 * to a dot, [positive] when the two rise together, [negative] when one falls as the other rises.
 * No dot without a value.
 */
@Composable
internal fun StrengthBar(rho: Double?, positive: Color, negative: Color, description: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val up = readableOn(positive, surface)
    val down = readableOn(negative, surface)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text("−1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = description }
        ) {
            val y = size.height / 2
            val bar = 6.dp.toPx()
            val radius = size.height / 2
            val middle = size.width / 2
            drawRoundRect(track, topLeft = Offset(0f, y - bar / 2), size = Size(size.width, bar), cornerRadius = CornerRadius(bar / 2))
            drawLine(tick, Offset(middle, 1f), Offset(middle, size.height - 1f), strokeWidth = 1.5.dp.toPx())
            if (rho != null) {
                val x = middle + rho.toFloat().coerceIn(-1f, 1f) * (middle - radius)
                val color = if (rho >= 0) up else down
                drawRoundRect(
                    color.copy(alpha = 0.45f),
                    topLeft = Offset(min(middle, x), y - bar / 2),
                    size = Size(abs(x - middle), bar),
                    cornerRadius = CornerRadius(bar / 2)
                )
                drawCircle(color, radius = radius, center = Offset(x, y))
            }
        }
        Text("+1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [accent] nudged toward white on a dark [background] (black on a light one) until it reaches
 * the 3:1 contrast Material asks of icons and marks: the chart colors are fixed, the theme isn't.
 */
internal fun readableOn(accent: Color, background: Color): Color {
    var color = accent
    val toward = if (background.luminance() < 0.5f) Color.White else Color.Black
    repeat(12) {
        if (contrast(color, background) >= 3f) return color
        color = lerp(color, toward, 0.12f)
    }
    return color
}

internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}
