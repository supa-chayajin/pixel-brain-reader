package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDashboardScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifeOSViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadData(java.time.LocalDate.now())
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { 
                    Column {
                        Text("Good Day!", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "${state.habitsWithStats.count { it.isCompletedToday }}/${state.habits.size} Habits done today", 
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.addDebugHabit() }) {
                Icon(Icons.Default.Add, contentDescription = "Add Habit")
            }
        }
    ) { padding ->
        if (state.habits.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No habits configured. Tap + to add one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // SECTION A: TODAY'S FOCUS
                item {
                    Text(
                        "Today's Focus",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.habitsWithStats) { habitStats ->
                            HabitActionCard(
                                habit = habitStats,
                                onToggle = { viewModel.toggleHabit(habitStats.config.id) }
                            )
                        }
                    }
                }

                // SECTION B: INSIGHTS
                item {
                    Text(
                        "Your Journey",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(state.habitsWithStats) { habitStats ->
                    HabitStreakRow(habitStats)
                }
            }
        }
    }
}

@Composable
fun HabitActionCard(habit: HabitWithStats, onToggle: () -> Unit) {
    val isDone = habit.isCompletedToday
    val containerColor by animateColorAsState(
        if (isDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    )
    val contentColor by animateColorAsState(
        if (isDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    )

    Card(
        onClick = onToggle,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.size(width = 140.dp, height = 180.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Icon(
                    imageVector = if (isDone) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = habit.config.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (isDone) {
                Text(
                    "Done!",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = FontWeight.Bold
                )
            } else {
                 Text(
                    "Do it",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun HabitStreakRow(habit: HabitWithStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon Placeholder (Initial)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                 Box(contentAlignment = Alignment.Center) {
                     Text(
                         habit.config.title.take(1),
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onSecondaryContainer
                     )
                 }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(habit.config.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "🔥 ${habit.currentStreak} day streak", 
                    style = MaterialTheme.typography.bodySmall,
                    color = if (habit.currentStreak > 2) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Heatmap
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            habit.history.forEach { done ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.3f),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}
