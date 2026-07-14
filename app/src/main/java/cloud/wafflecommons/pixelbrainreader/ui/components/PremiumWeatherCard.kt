package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*

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
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = "Weather unavailable",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
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
                val (icon, condition) = mapWeatherCode(weather.code)
                
                // Large Weather Icon
                Icon(
                    imageVector = icon,
                    contentDescription = condition,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

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

private fun mapWeatherCode(code: Int): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> {
    return when (code) {
        0 -> Icons.Rounded.WbSunny to "Clear Skies"
        1, 2, 3 -> Icons.Rounded.Cloud to "Partly Cloudy"
        45, 48 -> Icons.Rounded.Cloud to "Foggy"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.WaterDrop to "Rainy"
        71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit to "Snowy"
        95, 96, 99 -> Icons.Rounded.Thunderstorm to "Stormy"
        else -> Icons.Rounded.DeviceThermostat to "Unknown"
    }
}
