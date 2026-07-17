package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Whatshot
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.MoodTrendsCard
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint
import cloud.wafflecommons.pixelbrainreader.ui.theme.ChartPalette
import cloud.wafflecommons.pixelbrainreader.ui.theme.SemanticPalette

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
            statusText = (syncState as? SyncState.Syncing)?.step?.label,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalItemSpacing = 16.dp
                ) {
                    item { RpgHeroCard(uiState) }
                    item { TodayVitalsCard(uiState) }
                    item { MoodVsHeartRateChartCard(uiState.moodHistory) }
                    item { MoodSummaryCard(uiState) }
                    item { CompletionRingsCard(uiState, globalCompletion) }
                    item { HabitsSummaryCard(uiState, sleepDuration) }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) {
                    Text(text = trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

private fun moodEmoji(score: Float): String = when {
    score <= 0f -> "∅"
    score < 1.8f -> "😫"
    score < 2.6f -> "😞"
    score < 3.4f -> "😐"
    score < 4.2f -> "🙂"
    else -> "🤩"
}

// --- 1. RPG Hero (level / class / XP) ---
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RpgHeroCard(uiState: LifeStatsUiState) {
    val xpProgress = if (uiState.xpToNextLevel > 0)
        (uiState.currentXp.toFloat() / uiState.xpToNextLevel.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedXp by animateFloatAsState(
        targetValue = xpProgress,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "heroXp"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MilitaryTech,
                            contentDescription = "Level",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Niveau ${uiState.level}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        uiState.characterClass,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { animatedXp },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${uiState.currentXp} / ${uiState.xpToNextLevel} XP",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

// --- 2. Today's vitals (a 2-col tile grid) ---
@Composable
private fun TodayVitalsCard(uiState: LifeStatsUiState) {
    val stepPct = if (uiState.stepGoal > 0)
        ((uiState.todaySteps.toFloat() / uiState.stepGoal.toFloat()) * 100).toInt().coerceIn(0, 999) else 0
    val sleepH = uiState.todaySleepMinutes / 60
    val sleepM = uiState.todaySleepMinutes % 60
    val distFormatted = String.format(java.util.Locale.US, "%.1f", uiState.todayDistanceKm)

    DashboardCard(title = "Aujourd'hui") {
        val tiles = listOf(
            VitalTile("Pas", "${uiState.todaySteps}", "$stepPct% du but", Icons.Default.Speed, SemanticPalette.Success),
            VitalTile("Distance", "$distFormatted km", null, Icons.Default.Straighten, ChartPalette.Distance),
            VitalTile("Actif", "${uiState.todayActiveMinutes} min", null, Icons.Default.Timer, ChartPalette.ActiveMinutes),
            VitalTile("Calories", "${uiState.caloriesBurned} kcal", "brûlées", Icons.Default.LocalFireDepartment, ChartPalette.Calories),
            VitalTile("Rythme card.", "${uiState.avgHeartRate} BPM", null, Icons.Default.Favorite, ChartPalette.HeartRate),
            VitalTile("Sommeil", "${sleepH}h ${sleepM}m", null, Icons.Default.Bedtime, ChartPalette.Sleep),
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { StatTile(Modifier.weight(1f), it) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class VitalTile(val label: String, val value: String, val subtitle: String?, val icon: ImageVector, val tint: Color)

@Composable
private fun StatTile(modifier: Modifier, tile: VitalTile) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(tile.icon, contentDescription = tile.label, tint = tile.tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(tile.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tile.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (tile.subtitle != null) {
                    Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- 3. Mood vs Heart Rate chart ---
@Composable
private fun MoodVsHeartRateChartCard(moodHistory: List<LifeStatsMoodPoint>) {
    DashboardCard(title = "Humeur vs. Rythme cardiaque (7j)") {
        MoodTrendsCard(
            moodTrend = moodHistory.map {
                DailyMoodPoint(date = it.date, score = it.score, emoji = it.emoji, avgBpm = it.avgBpm)
            }
        )
    }
}

// --- 4. Mood summary (7d / 30d averages + best day) ---
@Composable
private fun MoodSummaryCard(uiState: LifeStatsUiState) {
    val realDays = uiState.moodHistory.filter { it.score > 0f }
    val bestDay = realDays.maxByOrNull { it.score }
    DashboardCard(title = "Humeur") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(moodEmoji(uiState.avgMood7Days), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                StatLine(Icons.Default.Mood, ChartPalette.Habits, "Moyenne 7j", String.format(java.util.Locale.US, "%.1f/5", uiState.avgMood7Days))
                StatLine(Icons.Default.Mood, ChartPalette.Meditation, "Moyenne 30j", String.format(java.util.Locale.US, "%.1f/5", uiState.avgMood30Days))
                if (bestDay != null) {
                    StatLine(Icons.Default.Whatshot, SemanticPalette.Success, "Meilleur jour", "${bestDay.emoji} ${String.format(java.util.Locale.US, "%.1f", bestDay.score)}")
                }
            }
        }
    }
}

@Composable
private fun StatLine(icon: ImageVector, tint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

// --- 5. Productivity rings (Tasks / Habits / Chores) ---
@Composable
private fun CompletionRingsCard(uiState: LifeStatsUiState, globalCompletion: Float) {
    val totalChores = uiState.criticalChoresCount + uiState.cleanChoresCount
    val choreRate = if (totalChores > 0) uiState.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
    DashboardCard(title = "Productivité (7j)", trailing = "${(globalCompletion * 100).toInt()}% global") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                ActivityRings(
                    tasksProgress = uiState.taskCompletionRate,
                    habitsProgress = uiState.habitCompletionRate,
                    choresProgress = choreRate
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                LegendItem("Tâches", ChartPalette.Tasks, "${(uiState.taskCompletionRate * 100).toInt()}%")
                LegendItem("Habitudes", ChartPalette.Habits, "${(uiState.habitCompletionRate * 100).toInt()}%")
                LegendItem("Ménage", ChartPalette.Chores, "${(choreRate * 100).toInt()}%")
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

// --- 6. Habits summary (done today / best streak / active / chores breakdown) ---
@Composable
private fun HabitsSummaryCard(uiState: LifeStatsUiState, sleepDurationMinutes: Long) {
    DashboardCard(title = "Habitudes & Ménage") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatLine(Icons.Default.Checklist, ChartPalette.Habits, "Faites aujourd'hui", "${uiState.completedHabitsToday}/${uiState.scheduledHabitsToday}")
            StatLine(Icons.Default.Whatshot, SemanticPalette.StreakAccent, "Meilleure série", "${uiState.bestHabitStreak} 🔥")
            StatLine(Icons.Default.Checklist, MaterialTheme.colorScheme.primary, "Habitudes actives", "${uiState.totalActiveHabits}")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StatLine(Icons.Default.LocalFireDepartment, ChartPalette.HeartRate, "Ménage critique", "${uiState.criticalChoresCount}")
            StatLine(Icons.Default.LocalFireDepartment, SemanticPalette.Warning, "Ménage à surveiller", "${uiState.warningChoresCount}")
        }
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
    val animTask by animateFloatAsState(targetValue = taskTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(), label = "ringTask")
    val animHabit by animateFloatAsState(targetValue = habitTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(), label = "ringHabit")
    val animChore by animateFloatAsState(targetValue = choreTarget, animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(), label = "ringChore")

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
