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
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.shape.shader.verticalGradient
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.decoration.ThresholdLine
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.component.shape.shader.DynamicShaders
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeStatsScreen(
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life Stats") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
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
            // 1. Sleep History
            if (state.sleepHistory.isNotEmpty()) {
                val sleepEntries = state.sleepHistory.mapIndexed { index, metric ->
                    FloatEntry(index.toFloat(), metric.value.toFloat())
                }
                val model = entryModelOf(sleepEntries)
                
                DashboardCard(title = "Sleep History (7 Days)") {
                    Chart(
                        chart = lineChart(
                            lines = listOf(
                                com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                    lineColor = Color(0xFF3F51B5), // Indigo
                                    lineBackgroundShader = verticalGradient(
                                        colors = arrayOf(Color(0xFF3F51B5).copy(alpha = 0.5f), Color(0xFF3F51B5).copy(alpha = 0f))
                                    )
                                )
                            ),
                            decorations = listOf(
                                ThresholdLine(
                                    thresholdValue = 7f,
                                    lineComponent = com.patrykandpatrick.vico.core.component.shape.ShapeComponent(
                                        shape = Shapes.rectShape,
                                        color = Color.Gray.toArgb(),
                                        strokeWidthDp = 1f,
                                        strokeColor = Color.Gray.toArgb()
                                    ),
                                    labelComponent = com.patrykandpatrick.vico.core.component.text.textComponent {
                                        this.color = Color.Gray.toArgb()
                                        this.textSizeSp = 10f
                                        this.padding.startDp = 8f
                                    }
                                )
                            )
                        ),
                        model = model,
                        startAxis = rememberStartAxis(
                            title = "Hours",
                            valueFormatter = { value, _ -> String.format("%.1fh", value) }
                        ),
                        bottomAxis = rememberBottomAxis(
                            valueFormatter = { value, _ ->
                                state.sleepHistory.getOrNull(value.toInt())?.date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                }
            }

            // 2. Steps History
            if (state.stepHistory.isNotEmpty()) {
                val stepEntries = state.stepHistory.mapIndexed { index, metric ->
                     FloatEntry(index.toFloat(), metric.value.toFloat())
                }
                val model = entryModelOf(stepEntries)

                DashboardCard(title = "Steps History") {
                    Chart(
                        chart = lineChart(
                            lines = listOf(
                                com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                    lineColor = Color(0xFFFF9800), // Orange
                                    lineBackgroundShader = verticalGradient(
                                        colors = arrayOf(Color(0xFFFF9800).copy(alpha = 0.5f), Color(0xFFFF9800).copy(alpha = 0f))
                                    )
                                )
                            ),
                            decorations = listOf(
                                ThresholdLine(
                                    thresholdValue = 7000f,
                                    lineComponent = com.patrykandpatrick.vico.core.component.shape.ShapeComponent(
                                        shape = Shapes.rectShape,
                                        color = Color.Gray.toArgb(),
                                        strokeWidthDp = 1f,
                                        strokeColor = Color.Gray.toArgb()
                                    )
                                )
                            )
                        ),
                        model = model,
                        startAxis = rememberStartAxis(title = "Steps"),
                        bottomAxis = rememberBottomAxis(
                            valueFormatter = { value, _ ->
                                state.stepHistory.getOrNull(value.toInt())?.date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                }
            }

            // 3. Weekly Correlation
            if (state.weeklyCorrelation.isNotEmpty()) {
                val hrEntries = state.weeklyCorrelation.mapIndexed { index, point ->
                    FloatEntry(index.toFloat(), point.avgBpm.toFloat())
                }
                val moodEntries = state.weeklyCorrelation.mapIndexed { index, point ->
                    FloatEntry(index.toFloat(), point.moodScore.toFloat() * 20f) // Scale 1-5 to 20-100 for graph visibility roughly
                }
                // Multi-line model
                val model = entryModelOf(hrEntries, moodEntries)

                DashboardCard(title = "Weekly Correlation (HR vs Mood)") {
                     Chart(
                        chart = lineChart(
                            lines = listOf(
                                com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = Color.Red),
                                com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = Color(0xFF9C27B0)) // Purple
                            )
                        ),
                        model = model,
                        startAxis = rememberStartAxis(title = "BPM / Mood (x20)"),
                        bottomAxis = rememberBottomAxis(
                            valueFormatter = { value, _ ->
                                state.weeklyCorrelation.getOrNull(value.toInt())?.date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                    Text(
                        "Red: Heart Rate, Purple: Mood",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. Today's Biometrics
            if (state.todayCorrelation.isNotEmpty()) {
                // Map timestamp to hour (0-24)
                val hrEntries = state.todayCorrelation.mapNotNull { point ->
                    if (point.bpm != null) {
                         val hour = point.timestamp.atZone(java.time.ZoneId.systemDefault()).hour + (point.timestamp.atZone(java.time.ZoneId.systemDefault()).minute / 60f)
                         FloatEntry(hour, point.bpm.toFloat())
                    } else null
                }
                
                val moodEntries = state.todayCorrelation.mapNotNull { point ->
                    if (point.moodScore != null) {
                         val hour = point.timestamp.atZone(java.time.ZoneId.systemDefault()).hour + (point.timestamp.atZone(java.time.ZoneId.systemDefault()).minute / 60f)
                         FloatEntry(hour, (point.moodScore.toFloat() * 20f)) 
                    } else null
                }

                // If lists are empty, Vico might crash or show nothing.
                if (hrEntries.isNotEmpty() || moodEntries.isNotEmpty()) {
                     val model = entryModelOf(hrEntries, moodEntries)

                     DashboardCard(title = "Today's Biometrics") {
                        Chart(
                            chart = lineChart(
                                lines = listOf(
                                    com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = Color.Red),
                                    com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                        lineColor = Color.Transparent, 
                                        point = com.patrykandpatrick.vico.core.component.shape.ShapeComponent(
                                                shape = Shapes.pillShape,
                                                color = Color(0xFF9C27B0).toArgb(),
                                                strokeWidthDp = 0f
                                        ),
                                        // pointSizeDp = 8f // Deprecated/Removed. Handled by ShapeComponent size if possible or Defaults. 
                                        // For now let's just use defaults or minimal config to pass build.
                                    )
                                )
                            ),
                            model = model,
                            startAxis = rememberStartAxis(),
                            bottomAxis = rememberBottomAxis(title = "Hour (0-24)"),
                            modifier = Modifier.height(200.dp)
                        )
                    }
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
