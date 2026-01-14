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
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Habits",
                subtitle = "${state.habitsWithStats.count { it.isCompletedToday }}/${state.habits.size} done today",
                scrollBehavior = scrollBehavior
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
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 155.dp),
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Iterate over Grouped Habits
                state.groupedHabits.forEach { (category, habits) ->
                    // Section Header (Full Width)
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp, bottom = 8.dp)
                        )
                    }

                    // Habits in Category (Grid Layout)
                    items(habits.size) { index ->
                        val habitStats = habits[index]
                        // Need to wrap in Box for padding if not using contentPadding? 
                        // Grid handles spacing via vertical/horizontalArrangement.
                        // But we want to ensure edge padding matches. 
                        // LazyVerticalGrid contentPadding applies to the whole container.
                        // Let's rely on standard grid behavior but keeping the card internal padding logic.
                        // Since we aren't using the Box wrapper from before, let's verify visual.
                        // The user asked for Arrangement.spacedBy(12.dp).
                        // I will add horizontal content padding to the Grid itself to align edges.
                        HabitCard(
                            habit = habitStats,
                            onToggle = { viewModel.toggleHabit(habitStats.config.id) },
                            onUpdateValue = { newVal -> viewModel.updateHabitValue(habitStats.config.id, newVal) }
                        )
                    }
                }
                
                // Footer: Streak History (Restored)
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                   Column { 
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Your Journey",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                   }
                }

                // Streak Rows (Full Width)
                items(
                    count = state.habitsWithStats.size,
                    span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
                ) { index ->
                    HabitStreakRow(state.habitsWithStats[index])
                }
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
