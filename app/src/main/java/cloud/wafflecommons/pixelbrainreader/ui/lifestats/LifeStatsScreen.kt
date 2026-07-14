package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.MoodTrendsCard
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint
import cloud.wafflecommons.pixelbrainreader.ui.theme.ChartPalette

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LifeStatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.finalUiState.collectAsStateWithLifecycle()
    val sleepDuration by viewModel.sleepDurationState.collectAsStateWithLifecycle()
    val globalCompletion by viewModel.globalCompletionState.collectAsStateWithLifecycle()
    val syncState by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isRefreshing = syncState is SyncState.Syncing

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(title = "Statistiques de Vie")
        }
    ) { innerPadding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.triggerSync() },
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { MoodVsHeartRateChartCard(uiState.moodHistory) }
                    item { HealthOverviewCard(uiState) }
                    item { DualInsightCards(sleepDuration, globalCompletion) }
                    item { HealthSummaryCard(uiState) }
                    item { CompletionRingsCard(uiState) }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun MoodVsHeartRateChartCard(moodHistory: List<LifeStatsMoodPoint>) {
    DashboardCard(title = "Mood vs. Heart Rate (7 Days)") {
        MoodTrendsCard(
            moodTrend = moodHistory.map {
                DailyMoodPoint(
                    date = it.date,
                    score = it.score,
                    emoji = it.emoji,
                    avgBpm = it.avgBpm
                )
            }
        )
    }
}

@Composable
private fun HealthSummaryCard(uiState: LifeStatsUiState) {
    DashboardCard(title = "Health Summary (7 Days)") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            HealthMetricItem(Modifier.weight(1f), "Calories", "${uiState.totalCalories} kcal", Icons.Default.LocalFireDepartment, ChartPalette.Calories)
            HealthMetricItem(Modifier.weight(1f), "Meditation", "${uiState.totalMeditationMinutes} min", Icons.Default.SelfImprovement, ChartPalette.Meditation)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            HealthMetricItem(Modifier.weight(1f), "Avg Heart Rate", "${uiState.avgHeartRate} BPM", Icons.Default.Favorite, ChartPalette.HeartRate)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HealthMetricItem(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, subtitle: String? = null) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HealthOverviewCard(uiState: LifeStatsUiState) {
    DashboardCard(title = "Health Overview (Today)") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val distFormatted = String.format(java.util.Locale.US, "%.1f", uiState.todayDistanceKm)
            HealthProgressRow("Distance", "$distFormatted km", (uiState.todayDistanceKm / 5.0).toFloat(), ChartPalette.Distance)
            HealthProgressRow("Active Minutes", "${uiState.todayActiveMinutes} min", uiState.todayActiveMinutes.toFloat() / 30f, ChartPalette.ActiveMinutes)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HealthProgressRow(title: String, value: String, progress: Float, color: Color) {
    var progressTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(progress) { progressTarget = progress.coerceIn(0f, 1f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>(),
        label = "healthProgress"
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(0.4f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.weight(0.6f).height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun CompletionRingsCard(uiState: LifeStatsUiState) {
    DashboardCard(title = "Productivity Hub (7 Days)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                // Chore completion mapping: Clean / (Critical + Clean)
                val totalChores = uiState.criticalChoresCount + uiState.cleanChoresCount
                val choreRate = if (totalChores > 0) uiState.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
                
                ActivityRings(
                    tasksProgress = uiState.taskCompletionRate,
                    habitsProgress = uiState.habitCompletionRate,
                    choresProgress = choreRate
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendItem("Tasks", ChartPalette.Tasks, "${(uiState.taskCompletionRate * 100).toInt()}%")
                LegendItem("Habits", ChartPalette.Habits, "${(uiState.habitCompletionRate * 100).toInt()}%")
                val totalChores = uiState.criticalChoresCount + uiState.cleanChoresCount
                val choreRate = if (totalChores > 0) uiState.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
                LegendItem("Clean Chores", ChartPalette.Chores, "${(choreRate * 100).toInt()}%")
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.size(12.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActivityRings(tasksProgress: Float, habitsProgress: Float, choresProgress: Float) {
    var taskTarget by remember { mutableStateOf(0f) }
    var habitTarget by remember { mutableStateOf(0f) }
    var choreTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(tasksProgress, habitsProgress, choresProgress) {
        taskTarget = tasksProgress
        habitTarget = habitsProgress
        choreTarget = choresProgress
    }
    val animTask by animateFloatAsState(targetValue = taskTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>(), label = "ringTask")
    val animHabit by animateFloatAsState(targetValue = habitTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>(), label = "ringHabit")
    val animChore by animateFloatAsState(targetValue = choreTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>(), label = "ringChore")

    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeW = 14.dp.toPx()
        val spacing = strokeW + 4.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        
        fun drawRing(radius: Float, progress: Float, color: Color) {
            drawCircle(color = color.copy(alpha = 0.2f), radius = radius, center = center, style = Stroke(strokeW))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(strokeW, cap = StrokeCap.Round)
            )
        }
        
        drawRing(size.width / 2 - strokeW / 2, animTask, ChartPalette.Tasks)
        drawRing(size.width / 2 - strokeW / 2 - spacing, animHabit, ChartPalette.Habits)
        drawRing(size.width / 2 - strokeW / 2 - (spacing * 2), animChore, ChartPalette.Chores)
    }
}

@Composable
private fun DualInsightCards(sleepDurationMinutes: Long, globalCompletion: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Card 1: Sleep & Recovery
        ElevatedCard(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Bedtime, contentDescription = "Sleep", tint = ChartPalette.Sleep, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("Sleep & Recovery", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                val hours = sleepDurationMinutes / 60
                val mins = sleepDurationMinutes % 60
                val sleepText = if (sleepDurationMinutes > 0) "${hours}h ${mins}m" else "--h --m"
                
                Text(sleepText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        
        // Card 2: Global Completion
        ElevatedCard(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.TaskAlt, contentDescription = "Completion", tint = ChartPalette.Completion, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("Productivité Globale", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                val percent = (globalCompletion * 100).toInt()
                Text("$percent% Complété", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}
