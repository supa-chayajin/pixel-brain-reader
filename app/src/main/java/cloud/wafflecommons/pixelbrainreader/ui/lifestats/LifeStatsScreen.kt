package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.shape.shader.verticalGradient
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeStatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val rpgStats by viewModel.rpgStats.collectAsStateWithLifecycle()
    val moodHistory by viewModel.moodHistory.collectAsStateWithLifecycle()
    val habitCompletionRates by viewModel.habitCompletionRates.collectAsStateWithLifecycle()
    val isHealthSynergyActive by viewModel.isHealthSynergyActive.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(title = "Statistiques de Vie")
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // 1. Gamification Radar Chart
            DashboardCard(title = "RPG Attributes") {
                if (rpgStats.isNotEmpty()) {
                    RadarChart(
                        stats = rpgStats,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                } else {
                    Text("No RPG stats available yet.")
                }
            }

            // 2. Statistics Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatisticCard(
                    title = "Habits Completion",
                    value = (habitCompletionRates * 100).toInt(),
                    unit = "%",
                    modifier = Modifier.weight(1f)
                )

                StatisticCard(
                    title = "Health Synergy",
                    value = if (isHealthSynergyActive) 1 else 0,
                    unit = if (isHealthSynergyActive) "Active" else "Inactive",
                    modifier = Modifier.weight(1f)
                )
            }

            // 3. Mood History Vico Chart
            DashboardCard(title = "Mood History (7 Days)") {
                if (moodHistory.isNotEmpty()) {
                    Chart(
                        chart = lineChart(
                            lines = listOf(
                                com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                    lineColor = Color(0xFF9C27B0), // Purple
                                    lineBackgroundShader = verticalGradient(
                                        colors = arrayOf(Color(0xFF9C27B0).copy(alpha = 0.5f), Color(0xFF9C27B0).copy(alpha = 0f))
                                    )
                                )
                            )
                        ),
                        model = entryModelOf(moodHistory),
                        startAxis = rememberStartAxis(title = "Mood Score"),
                        bottomAxis = rememberBottomAxis(title = "Days Ago"),
                        modifier = Modifier.height(200.dp)
                    )
                } else {
                    Text("No mood history available yet.")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun DashboardCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}
