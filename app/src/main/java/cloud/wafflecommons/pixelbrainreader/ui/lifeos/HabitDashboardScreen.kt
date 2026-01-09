package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Habits") }
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.habits) { habit ->
                    HabitCard(habit, state.logs[habit.id]?.firstOrNull(), onCheckIn = { viewModel.toggleHabit(habit.id) })
                }
            }
        }
    }
}

@Composable
fun HabitCard(habit: HabitConfig, log: cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry?, onCheckIn: () -> Unit) {
    val isDone = log?.status == HabitStatus.COMPLETED
    
    Card(onClick = onCheckIn) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(habit.title, style = MaterialTheme.typography.titleMedium)
                if (habit.description.isNotEmpty()) {
                    Text(habit.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isDone) {
                Icon(Icons.Default.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onCheckIn) {
                    Text("Check In")
                }
            }
        }
    }
}
