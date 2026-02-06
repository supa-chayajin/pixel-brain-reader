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
import com.patrykandpatrick.vico.core.marker.Marker
import com.patrykandpatrick.vico.core.component.text.textComponent
import com.patrykandpatrick.vico.core.component.shape.ShapeComponent

import com.patrykandpatrick.vico.core.component.marker.MarkerComponent
import android.graphics.Typeface
import com.patrykandpatrick.vico.core.dimensions.MutableDimensions
import com.patrykandpatrick.vico.core.context.MeasureContext
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeStatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
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
            // 0. Quick Stats (Animated)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val lastSteps = state.stepData.lastOrNull()?.y?.toInt() ?: 0
                val lastSleep = state.sleepData.lastOrNull()?.y?.toInt() ?: 0
                
                StatisticCard(
                    title = "Recent Steps",
                    value = lastSteps,
                    unit = "steps",
                    modifier = Modifier.weight(1f)
                )
                
                StatisticCard(
                    title = "Last Sleep",
                    value = lastSleep,
                    unit = "h",
                    modifier = Modifier.weight(1f)
                )
            }

            // 1. Sleep History
            if (state.sleepData.isNotEmpty()) {
                val model = entryModelOf(state.sleepData)
                
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
                                state.sleepLabels[value] ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                }
            } else {
                 // Empty State
                 DashboardCard(title = "Sleep History") {
                     Text("No sleep data available yet.")
                 }
            }

            // 2. Steps History
            if (state.stepData.isNotEmpty()) {
                val model = entryModelOf(state.stepData)

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
                                state.stepLabels[value] ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                }
            } else {
                 // Empty State
                 DashboardCard(title = "Steps History") {
                     Text("No step data available yet.")
                 }
            }

            // 3. Weekly Correlation
            if (state.weeklyHrData.isNotEmpty() && state.weeklyMoodData.isNotEmpty()) {
                // Multi-line model
                val model = entryModelOf(state.weeklyHrData, state.weeklyMoodData)

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
                        marker = rememberEmojiMarker(),
                        bottomAxis = rememberBottomAxis(
                            valueFormatter = { value, _ ->
                                state.weeklyLabels[value] ?: ""
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
            } else {
                 // Empty State
                 DashboardCard(title = "Weekly Correlation") {
                     Text("No correlation data available yet.")
                 }
            }

            // 4. Today's Biometrics
            if (state.todayHrData.isNotEmpty() || state.todayMoodData.isNotEmpty()) {
                 // For today, we use todayLabels
                 val model = if (state.todayHrData.isNotEmpty() && state.todayMoodData.isNotEmpty()) {
                     entryModelOf(state.todayHrData, state.todayMoodData)
                 } else if (state.todayHrData.isNotEmpty()) {
                     entryModelOf(state.todayHrData)
                 } else {
                     entryModelOf(state.todayMoodData)
                 }

                 DashboardCard(title = "Today's Biometrics") {
                    Chart(
                        chart = lineChart(
                            lines = if (state.todayHrData.isNotEmpty() && state.todayMoodData.isNotEmpty()) {
                                listOf(
                                    com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = Color.Red),
                                    com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                        lineColor = Color.Transparent, 
                                        point = com.patrykandpatrick.vico.core.component.shape.ShapeComponent(
                                                shape = Shapes.pillShape,
                                                color = Color(0xFF9C27B0).toArgb(),
                                                strokeWidthDp = 0f
                                        )
                                    )
                                )
                            } else if (state.todayHrData.isNotEmpty()) {
                                 listOf(com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = Color.Red))
                            } else {
                                 listOf(
                                     com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                                        lineColor = Color.Transparent, 
                                        point = com.patrykandpatrick.vico.core.component.shape.ShapeComponent(
                                                shape = Shapes.pillShape,
                                                color = Color(0xFF9C27B0).toArgb(),
                                                strokeWidthDp = 0f
                                        )
                                    )
                                 )
                            },
                        ),
                        model = model,
                        startAxis = rememberStartAxis(),
                        marker = rememberEmojiMarker(),
                        bottomAxis = rememberBottomAxis(
                            title = "Time",
                            valueFormatter = { value, _ ->
                                state.todayLabels[value] ?: ""
                            }
                        ),
                        modifier = Modifier.height(200.dp)
                    )
                }
            } else {
                 DashboardCard(title = "Today's Biometrics") {
                     Text("No biometric data for today.")
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

@Composable
fun rememberEmojiMarker(): Marker {
    val label = textComponent {
        color = Color.Black.toArgb()
        textSizeSp = 24f 
        typeface = Typeface.DEFAULT
    }

    val indicator = ShapeComponent(
        shape = Shapes.pillShape,
        color = Color(0xFF9C27B0).toArgb() 
    )

    return remember(label, indicator) {
        object : MarkerComponent(label, indicator, null) {
            init {
                labelFormatter = com.patrykandpatrick.vico.core.marker.MarkerLabelFormatter { markedEntries, _ ->
                    val entry = markedEntries.firstOrNull()?.entry
                    val y = entry?.y ?: 0f
                    if (y >= 1f && y <= 5f) {
                         when(Math.round(y)) {
                            1 -> "😫"
                            2 -> "😞"
                            3 -> "😐"
                            4 -> "🙂"
                            5 -> "🤩"
                            else -> String.format("%.1f", y)
                        }
                    } else {
                        String.format("%.0f", y) 
                    }
                }
            }
        }
    }
}
