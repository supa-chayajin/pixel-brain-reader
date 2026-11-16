package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.graphics.shapes.RoundedPolygon

/** What the sky does, from the forecast's WMO weather code (Open-Meteo). */
enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, STORM }

/** The WMO code's sky (pure): 0 clear, 1–2 partly cloudy, 3 overcast, fog, drizzle and rain, snow, storms. */
fun weatherKindOf(code: Int): WeatherKind = when (code) {
    0 -> WeatherKind.CLEAR
    1, 2 -> WeatherKind.PARTLY_CLOUDY
    3 -> WeatherKind.CLOUDY
    45, 48 -> WeatherKind.FOG
    in 51..67, in 80..82 -> WeatherKind.RAIN
    in 71..77, 85, 86 -> WeatherKind.SNOW
    in 95..99 -> WeatherKind.STORM
    else -> WeatherKind.CLOUDY
}

/**
 * The weather drawn live from Material 3 Expressive shapes (13.2, the owner's pick): the sun a
 * turning sunny burst, clouds puffy shapes drifting, rain pills falling, snow turning flakes, a
 * storm's bolt flashing, fog sliding bands. Every colour is a role of the device's scheme.
 * Decorative: the condition is written beside it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveWeather(
    kind: WeatherKind,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    /** What the scene is drawn in: a colour that reads on what lies behind it. */
    ink: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    /** The sun's colour. */
    sun: Color = MaterialTheme.colorScheme.primary
) {
    val loop = rememberInfiniteTransition(label = "weather")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    val drift by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val fall by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "fall")
    val flash by loop.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 2_800
                0f at 0
                0f at 2_000
                1f at 2_080
                0.25f at 2_200
                1f at 2_280
                0f at 2_600
            }
        ),
        label = "flash"
    )
    val u = size / 96f
    // Clouds are drawn opaque and faded as a whole (no seams where their parts overlap).
    val front = ink
    val back = ink

    Box(modifier = modifier.size(size)) {
        when (kind) {
            WeatherKind.CLEAR -> Piece(MaterialShapes.Sunny, sun, 76 * u, 10 * u, 10 * u, rotation = spin)
            WeatherKind.PARTLY_CLOUDY -> {
                Piece(MaterialShapes.Sunny, sun, 54 * u, 8 * u, 6 * u, rotation = spin)
                Cloud(front, x = (26 + drift * 4) * u, y = 40 * u, width = 64 * u)
            }
            WeatherKind.CLOUDY -> {
                Cloud(back, x = (6 - drift * 3) * u, y = 18 * u, width = 58 * u, alpha = 0.5f)
                Cloud(front, x = (26 + drift * 4) * u, y = 40 * u, width = 64 * u)
            }
            WeatherKind.FOG -> listOf(24f, 42f, 60f).forEachIndexed { i, y ->
                val sway = if (i % 2 == 0) drift else -drift
                Piece(MaterialShapes.Pill, ink, 76 * u, (10 + sway * 6) * u, y * u, height = 12 * u, alpha = if (i == 1) 0.5f else 0.9f)
            }
            WeatherKind.RAIN, WeatherKind.SNOW, WeatherKind.STORM -> {
                Cloud(front, x = (14 + drift * 3) * u, y = 8 * u, width = 68 * u)
                when (kind) {
                    WeatherKind.RAIN -> listOf(28f, 46f, 64f).forEachIndexed { i, x ->
                        val phase = (fall + i / 3f) % 1f
                        Piece(MaterialShapes.Pill, ink, 7 * u, x * u, (60 + phase * 26) * u, height = 16 * u, alpha = 1f - phase)
                    }
                    WeatherKind.SNOW -> listOf(26f, 46f, 66f).forEachIndexed { i, x ->
                        val phase = ((fall * 0.6f) + i / 3f) % 1f
                        Piece(MaterialShapes.Cookie6Sided, ink, 12 * u, (x + drift * 3) * u, (60 + phase * 24) * u, rotation = spin * 2, alpha = 1f - phase)
                    }
                    else -> Box(
                        modifier = Modifier
                            .offset(x = 32 * u, y = 50 * u)
                            .graphicsLayer { alpha = flash }
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = sun, modifier = Modifier.size(38 * u))
                    }
                }
            }
        }
    }
}

/** A cloud: a pill for its base, a scalloped puffy bump and a round one on top. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Cloud(color: Color, x: Dp, y: Dp, width: Dp, alpha: Float = 1f) {
    val base = width * 0.42f
    // One offscreen layer for the whole cloud: its parts merge, then the layer fades.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
    ) {
        Piece(MaterialShapes.Puffy, color, width * 0.56f, x + width * 0.14f, y, height = width * 0.56f)
        Piece(MaterialShapes.Circle, color, width * 0.4f, x + width * 0.52f, y + width * 0.14f)
        Piece(MaterialShapes.Pill, color, width, x, y + width * 0.3f, height = base)
    }
}

/** One shape of the scene, at ([x], [y]) in the box, [width] × [height], turned and faded. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Piece(
    polygon: RoundedPolygon,
    color: Color,
    width: Dp,
    x: Dp,
    y: Dp,
    height: Dp = width,
    rotation: Float = 0f,
    alpha: Float = 1f
) {
    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = width, height = height)
            .graphicsLayer {
                rotationZ = rotation
                this.alpha = alpha
            }
            .clip(polygon.toShape())
            .background(color)
    )
}
