package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsights
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsightsRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Honest health insights (L1, 12.20): re-read each day; null while reading. */
@HiltViewModel
class HealthInsightsViewModel @Inject constructor(
    private val repository: HealthInsightsRepository,
    appClock: AppClock
) : ViewModel() {
    private val _insights = MutableStateFlow<List<HealthInsights.Insight>?>(null)
    val insights: StateFlow<List<HealthInsights.Insight>?> = _insights.asStateFlow()

    init {
        viewModelScope.launch {
            appClock.today.collectLatest { today ->
                _insights.value = try {
                    repository.insights(today)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }
}

/**
 * How your measures move together over 90 days (L1, the owner's D10), each pair as a strength bar
 * (12.21, D8): its two icons, where ρ sits between −1 and +1, the words and N. Each pair shows
 * from 14 paired days, and the card says a correlation is not a cause.
 */
@Composable
fun HealthInsightsCard(viewModel: HealthInsightsViewModel = hiltViewModel()) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    StatsCard(
        title = "Honest insights",
        icon = Icons.Rounded.Insights,
        accent = MaterialTheme.colorScheme.tertiary,
        trailing = "${HealthInsights.WINDOW_DAYS} days"
    ) {
        when (val list = insights) {
            null -> Text("Reading your days…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                list.forEach { InsightRow(it) }
            }
        }
        Text(
            "A correlation is not a cause: these say what moves together, never why.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InsightRow(insight: HealthInsights.Insight) {
    val (from, to) = linkIcons(insight.link)
    val rho = insight.rho?.takeIf { insight.enoughDays }
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.tertiary
    val caption = when {
        !insight.enoughDays -> "Not enough days yet: ${insight.n} of ${HealthInsights.MIN_DAYS}"
        rho == null -> "No variation to compare"
        else -> HealthInsights.words(rho) + " · ρ " + String.format(Locale.ROOT, "%.2f", rho)
    }
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(from, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
            Icon(to, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(insight.link.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            LabelChip("N ${insight.n}")
        }
        StrengthBar(rho = rho, positive = positive, negative = negative, description = "${insight.link.label}: $caption")
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                rho == null -> MaterialTheme.colorScheme.onSurfaceVariant
                rho >= 0 -> readableOn(positive, MaterialTheme.colorScheme.surfaceContainer)
                else -> readableOn(negative, MaterialTheme.colorScheme.surfaceContainer)
            }
        )
    }
}

/** What comes first and what may follow, as icons. */
private fun linkIcons(link: HealthInsights.Link): Pair<ImageVector, ImageVector> = when (link) {
    HealthInsights.Link.SLEEP_MOOD -> Icons.Rounded.Bedtime to Icons.Rounded.Mood
    HealthInsights.Link.STEPS_SLEEP -> Icons.AutoMirrored.Rounded.DirectionsWalk to Icons.Rounded.Bedtime
    HealthInsights.Link.SLEEP_TASKS -> Icons.Rounded.Bedtime to Icons.Rounded.TaskAlt
    HealthInsights.Link.MOOD_HRV -> Icons.Rounded.Mood to Icons.Rounded.MonitorHeart
}
