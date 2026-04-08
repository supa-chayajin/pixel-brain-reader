package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.MoodTrendsCard
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeStatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.finalUiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(title = "Statistiques de Vie")
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = innerPadding,
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { MoodVsHeartRateChartCard(uiState.moodHistory) }
            item { HealthSummaryCard(uiState) }
            item { CompletionRingsCard(uiState) }
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
            HealthMetricItem(Modifier.weight(1f), "Calories", "${uiState.totalCalories} kcal", Icons.Default.LocalFireDepartment, Color(0xFFFFA726))
            HealthMetricItem(Modifier.weight(1f), "Meditation", "${uiState.totalMeditationMinutes} min", Icons.Default.SelfImprovement, Color(0xFF29B6F6))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            HealthMetricItem(Modifier.weight(1f), "Avg Heart Rate", "${uiState.avgHeartRate} BPM", Icons.Default.Favorite, Color(0xFFFF5252))
            HealthMetricItem(Modifier.weight(1f), "Synergy", if (uiState.isHealthSynergyActive) "Active" else "Inactive", Icons.Default.Favorite, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HealthMetricItem(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
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
                LegendItem("Tasks", Color(0xFFE91E63), "${(uiState.taskCompletionRate * 100).toInt()}%")
                LegendItem("Habits", Color(0xFF8BC34A), "${(uiState.habitCompletionRate * 100).toInt()}%")
                val totalChores = uiState.criticalChoresCount + uiState.cleanChoresCount
                val choreRate = if (totalChores > 0) uiState.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
                LegendItem("Clean Chores", Color(0xFF03A9F4), "${(choreRate * 100).toInt()}%")
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

@Composable
private fun ActivityRings(tasksProgress: Float, habitsProgress: Float, choresProgress: Float) {
    val animTask by animateFloatAsState(targetValue = tasksProgress, animationSpec = tween(1500))
    val animHabit by animateFloatAsState(targetValue = habitsProgress, animationSpec = tween(1500))
    val animChore by animateFloatAsState(targetValue = choresProgress, animationSpec = tween(1500))

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
        
        drawRing(size.width / 2 - strokeW / 2, animTask, Color(0xFFE91E63))
        drawRing(size.width / 2 - strokeW / 2 - spacing, animHabit, Color(0xFF8BC34A))
        drawRing(size.width / 2 - strokeW / 2 - (spacing * 2), animChore, Color(0xFF03A9F4))
    }
}
