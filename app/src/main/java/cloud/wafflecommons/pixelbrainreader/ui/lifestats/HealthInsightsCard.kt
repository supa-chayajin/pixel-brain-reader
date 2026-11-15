package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
 * How your measures move together over 90 days (L1, the owner's D10): each pair once it has 14
 * paired days, with N, in plain words, and the reminder that a correlation is not a cause.
 */
@Composable
fun HealthInsightsCard(viewModel: HealthInsightsViewModel = hiltViewModel()) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Honest insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${HealthInsights.WINDOW_DAYS} days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            when (val list = insights) {
                null -> Text("Reading your days…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> list.forEachIndexed { index, insight ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
                    InsightRow(insight)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "A correlation is not a cause: these say what moves together, never why.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightRow(insight: HealthInsights.Insight) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(insight.link.label, style = MaterialTheme.typography.bodyLarge)
        val rho = insight.rho
        Text(
            text = when {
                !insight.enoughDays -> "Not enough days yet: ${insight.n} of ${HealthInsights.MIN_DAYS}"
                rho == null -> "No variation to compare · N = ${insight.n}"
                else -> HealthInsights.words(rho) + " · ρ = " + String.format(Locale.ROOT, "%.2f", rho) + " · N = ${insight.n}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rho != null && insight.enoughDays) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
