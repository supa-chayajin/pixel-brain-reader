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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.ui.theme.SemanticPalette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HabitDashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToStats: () -> Unit,
    viewModel: LifeOSViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val todayHabits by viewModel.todayHabits.collectAsStateWithLifecycle()
    val syncState by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isRefreshing = syncState is SyncState.Syncing

    // View toggle: false = today's scheduled habits (default), true = every habit.
    var showAll by rememberSaveable { mutableStateOf(false) }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Habits",
                subtitle = "${todayHabits.count { it.isCompletedToday }}/${todayHabits.size} done today",
                scrollBehavior = scrollBehavior,
                actions = {
                    FilledTonalIconButton(
                        onClick = onNavigateToStats,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ShowChart, "View Statistics")
                    }
                }
            )
        }
    ) { padding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.forceSyncEverything()
            },
            statusText = (syncState as? SyncState.Syncing)?.step?.label,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Loading habits from Vault...")
                    }
                }
            } else if (state.habits.isEmpty()) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No habits configured.\nGo to Settings > Life OS Automations to set them up.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 155.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NavBarClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // [NEW] Hero Card (Full Width)
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                     cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 0) {
                         cloud.wafflecommons.pixelbrainreader.ui.gamification.HeroCard(
                             state = state.gamificationState,
                             modifier = Modifier.padding(16.dp)
                         )
                     }
                }

                // View toggle: today's scheduled habits vs. every habit.
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        SegmentedButton(
                            selected = !showAll,
                            onClick = { showAll = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Today") }
                        SegmentedButton(
                            selected = showAll,
                            onClick = { showAll = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("All (${state.habits.size})") }
                    }
                }

                val habitsToShow = if (showAll) state.allGroupedHabits else state.groupedHabits

                if (habitsToShow.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (showAll) "No habits configured."
                                else "No habits scheduled for today. Enjoy your rest!",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Iterate over Grouped Habits
                    habitsToShow.forEach { (category, habits) ->
                        // Section Header (Full Width)
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 0) {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 16.dp, bottom = 8.dp)
                                )
                            }
                        }

                        // Habits in Category (Grid Layout)
                        items(count = habits.size, key = { index -> habits[index].config.id }) { index ->
                            val habitStats = habits[index]
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = index + 1, modifier = Modifier.animateItem()) {
                                HabitCard(
                                    habit = habitStats,
                                    onToggle = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        viewModel.toggleHabit(habitStats.config.id)
                                    },
                                    onUpdateValue = { newVal -> viewModel.updateHabitValue(habitStats.config.id, newVal) }
                                )
                            }
                        }
                    }
                } // End of else
            } // End of LazyVerticalGrid
            } // End of else block
        } // End of PullToRefreshBox
    } // End of Scaffold content
} // End of HabitDashboardScreen

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
                    color = if (habit.currentStreak > 2) SemanticPalette.StreakAccent else MaterialTheme.colorScheme.onSurfaceVariant
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
