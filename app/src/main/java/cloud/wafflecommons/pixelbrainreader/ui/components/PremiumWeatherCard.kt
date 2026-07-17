package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PremiumWeatherCard(
    weather: WeatherData?,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (weather == null || weather.temperature == "--°C") {
                // Loading State
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Column {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Fetching fresh weather data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            } else if (weather.code == -2) {
                // Unavailable State (code -2, set by DailyNoteViewModel.weatherUnavailable):
                // distinct from loading so the card never spins forever when location is
                // denied or the network/parse fails.
                AliveWeatherIcon(R.drawable.weather_offline, "Weather unavailable")
                Column {
                    Text(
                        text = "Weather unavailable",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = weather.location ?: "Check location permission",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            } else {
                val (iconRes, condition) = mapWeatherCode(weather.code)

                // Large, colourful, gently-animated weather illustration
                AliveWeatherIcon(iconRes, condition)

                Column {
                    Text(
                        text = weather.temperature,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "$condition • ${weather.location ?: "Unknown"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * A weather illustration that "breathes" — a gentle vertical bob + subtle scale pulse on an
 * infinite loop so the card feels alive without being distracting.
 */
@Composable
private fun AliveWeatherIcon(
    @DrawableRes resId: Int,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "weather")
    val bob by transition.animateFloat(
        initialValue = -2.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                translationY = bob.dp.toPx()
                scaleX = breathe
                scaleY = breathe
            }
    )
}

private fun mapWeatherCode(code: Int): Pair<Int, String> {
    return when (code) {
        0 -> R.drawable.weather_sunny to "Clear Skies"
        1, 2, 3 -> R.drawable.weather_partly_cloudy to "Partly Cloudy"
        45, 48 -> R.drawable.weather_fog to "Foggy"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.weather_rain to "Rainy"
        71, 73, 75, 77, 85, 86 -> R.drawable.weather_snow to "Snowy"
        95, 96, 99 -> R.drawable.weather_storm to "Stormy"
        else -> R.drawable.weather_unknown to "Unknown"
    }
}
