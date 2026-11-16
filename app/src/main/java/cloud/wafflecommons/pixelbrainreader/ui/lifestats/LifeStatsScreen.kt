@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.rounded.Star

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LifeStatsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHabits: () -> Unit = {},
    onNavigateToChores: () -> Unit = {},
    /** Your year so far (13.0, X5). */
    onOpenWrapped: () -> Unit = {},
    viewModel: LifeStatsViewModel = hiltViewModel(),
    modesViewModel: cloud.wafflecommons.pixelbrainreader.ui.modes.ModesViewModel = hiltViewModel()
) {
    val uiState by viewModel.finalUiState.collectAsStateWithLifecycle()
    val visibility by modesViewModel.visibility.collectAsStateWithLifecycle()
    val globalCompletion by viewModel.globalCompletionState.collectAsStateWithLifecycle()
    val syncState by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isRefreshing = syncState is SyncState.Syncing

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Life Stats",
                icon = Icons.Rounded.Star,
                actions = {
                    // X5 (13.0): first, so Habits and Chores keep their places at the end.
                    FilledTonalIconButton(
                        onClick = onOpenWrapped, shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, "Your year so far")
                    }
                    FilledTonalIconButton(
                        onClick = onNavigateToHabits, shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Rounded.DateRange, "Habits")
                    }
                    FilledTonalIconButton(
                        onClick = onNavigateToChores, shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Rounded.CleaningServices, "Chores")
                    }
                }
            )
        }
    ) { innerPadding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.triggerSync() },
            statusText = (syncState as? SyncState.Syncing)?.step?.label,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            if (visibility.hideHealth) {
                cloud.wafflecommons.pixelbrainreader.ui.modes.HealthPrivacyGate(
                    hidden = true,
                    onReveal = { modesViewModel.setScreenShareSafe(false) }
                ) {}
            } else if (uiState.isLoading) {
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
                    item { RecoveryCard() }
                    item { HealthInsightsCard() }
                    item { ExperimentsCard() }
                    item { MoodVsHeartRateChartCard(uiState.moodHistory) }
                    item { MoodSummaryCard(uiState) }
                    item { CompletionRingsCard(uiState, globalCompletion) }
                    item { HabitsSummaryCard(uiState) }
                }
            }
        }
    }
}

// --- 1. RPG Hero (level / class / XP): the page's lead, in the primary container ---
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
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.MilitaryTech,
                        contentDescription = "Level",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Level ${uiState.level}",
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
            Spacer(Modifier.height(18.dp))
            // The level's progress, as an expressive wave (13.2).
            val waveStroke = with(LocalDensity.current) { Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round) }
            LinearWavyProgressIndicator(
                progress = { animatedXp },
                modifier = Modifier.fillMaxWidth().height(14.dp),
                // On the hero's primary container, its own content colour reads (primary didn't).
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                stroke = waveStroke,
                trackStroke = waveStroke
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

// --- 2. Today's vitals: big numbers in tiles, two to a row ---
@Composable
private fun TodayVitalsCard(uiState: LifeStatsUiState) {
    val stepPct = if (uiState.stepGoal > 0)
        ((uiState.todaySteps.toFloat() / uiState.stepGoal.toFloat()) * 100).toInt().coerceIn(0, 999) else 0
    val sleepH = uiState.todaySleepMinutes / 60
    val sleepM = uiState.todaySleepMinutes % 60
    val distFormatted = String.format(java.util.Locale.US, "%.1f", uiState.todayDistanceKm)

    StatsCard(title = "Today", icon = Icons.Rounded.Today, accent = ChartPalette.Distance) {
        val tiles = listOf(
            VitalTile("Steps", "${uiState.todaySteps}", "$stepPct% of goal", Icons.Rounded.Speed, SemanticPalette.Success),
            VitalTile("Distance", "$distFormatted km", null, Icons.Rounded.Straighten, ChartPalette.Distance),
            VitalTile("Active", "${uiState.todayActiveMinutes} min", null, Icons.Rounded.Timer, ChartPalette.ActiveMinutes),
            VitalTile("Calories", "${uiState.caloriesBurned} kcal", "burned", Icons.Rounded.LocalFireDepartment, ChartPalette.Calories),
            VitalTile("Heart rate", "${uiState.avgHeartRate} bpm", null, Icons.Rounded.Favorite, ChartPalette.HeartRate),
            VitalTile("Sleep", "${sleepH}h ${sleepM}m", null, Icons.Rounded.Bedtime, ChartPalette.Sleep),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { StatsTile(it.label, it.value, it.icon, it.tint, Modifier.weight(1f), note = it.subtitle) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class VitalTile(val label: String, val value: String, val subtitle: String?, val icon: ImageVector, val tint: Color)

// --- 3. Mood and heart rate, side by side over the week ---
@Composable
private fun MoodVsHeartRateChartCard(moodHistory: List<LifeStatsMoodPoint>) {
    StatsCard(title = "Mood & heart rate", icon = Icons.AutoMirrored.Rounded.ShowChart, accent = ChartPalette.HeartRate, trailing = "7 days") {
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
    StatsCard(title = "Mood", icon = Icons.Rounded.Mood, accent = ChartPalette.Calories) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(uiState.avgMood7DaysEmoji, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                StatLine(Icons.Rounded.Mood, ChartPalette.Habits, "7-day average", String.format(java.util.Locale.US, "%.1f/5", uiState.avgMood7Days))
                StatLine(Icons.Rounded.Mood, ChartPalette.Meditation, "30-day average", String.format(java.util.Locale.US, "%.1f/5", uiState.avgMood30Days))
                if (bestDay != null) {
                    StatLine(Icons.Rounded.Whatshot, SemanticPalette.Success, "Best day", "${bestDay.emoji} ${String.format(java.util.Locale.US, "%.1f", bestDay.score)}")
                }
            }
        }
    }
}

@Composable
private fun StatLine(icon: ImageVector, tint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = readableOn(tint, MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.size(20.dp))
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
    StatsCard(
        title = "Productivity",
        icon = Icons.Rounded.DonutLarge,
        accent = ChartPalette.Tasks,
        trailing = "7 days · ${(globalCompletion * 100).toInt()}%"
    ) {
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
                LegendItem("Tasks", ChartPalette.Tasks, "${(uiState.taskCompletionRate * 100).toInt()}%")
                LegendItem("Habits", ChartPalette.Habits, "${(uiState.habitCompletionRate * 100).toInt()}%")
                LegendItem("Chores", ChartPalette.Chores, "${(choreRate * 100).toInt()}%")
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(12.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

// --- 6. Habits and chores: today, streaks, what needs attention ---
@Composable
private fun HabitsSummaryCard(uiState: LifeStatsUiState) {
    StatsCard(title = "Habits & chores", icon = Icons.Rounded.Checklist, accent = ChartPalette.Habits) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatsTile("Done today", "${uiState.completedHabitsToday}/${uiState.scheduledHabitsToday}", Icons.Rounded.TaskAlt, ChartPalette.Habits, Modifier.weight(1f))
            StatsTile("Best streak", "${uiState.bestHabitStreak}", Icons.Rounded.Whatshot, SemanticPalette.StreakAccent, Modifier.weight(1f))
            StatsTile("Active", "${uiState.totalActiveHabits}", Icons.Rounded.Checklist, ChartPalette.Completion, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatsTile("Critical chores", "${uiState.criticalChoresCount}", Icons.Rounded.LocalFireDepartment, ChartPalette.HeartRate, Modifier.weight(1f))
            StatsTile("To watch", "${uiState.warningChoresCount}", Icons.Rounded.Visibility, SemanticPalette.Warning, Modifier.weight(1f))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActivityRings(tasksProgress: Float, habitsProgress: Float, choresProgress: Float) {
    var taskTarget by remember { mutableFloatStateOf(0f) }
    var habitTarget by remember { mutableFloatStateOf(0f) }
    var choreTarget by remember { mutableFloatStateOf(0f) }
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
