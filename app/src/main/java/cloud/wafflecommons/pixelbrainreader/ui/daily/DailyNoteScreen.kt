package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader
import cloud.wafflecommons.pixelbrainreader.ui.journal.MorningBriefingSection
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyNoteScreen(
    onNavigateBack: () -> Unit,
    onEditClicked: (String) -> Unit,
    onCheckInClicked: () -> Unit,
    onOpenHabits: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isGlobalSyncing: Boolean = false,
    viewModel: DailyNoteViewModel = hiltViewModel(),
    lifeOSViewModel: cloud.wafflecommons.pixelbrainreader.ui.lifeos.LifeOSViewModel = hiltViewModel() // Kept for legacy/context if needed
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // val lifeOsState by lifeOSViewModel.uiState.collectAsStateWithLifecycle()  // Usage replaced by Room Data

    var showAddTimelineDialog by remember { mutableStateOf(false) }
    var showAddTaskDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CortexTopAppBar(
                title = "Cortex",
                subtitle = state.date.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
                actions = {
                    // Emergency Sync
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(4.dp),
                            color = MaterialTheme.colorScheme.error,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.triggerEmergencySync() }) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Force Push",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Refresh
                    FilledTonalIconButton(
                        onClick = { viewModel.refreshDailyData() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }

                    // Settings
                    FilledTonalIconButton(
                        onClick = onNavigateToSettings,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = onCheckInClicked,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.Mood, contentDescription = "Mood")
                }

                ExtendedFloatingActionButton(
                    onClick = {
                        val dateStr = state.date.format(DateTimeFormatter.ISO_DATE)
                        onEditClicked("10_Journal/$dateStr.md")
                    },
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text("Editor") }
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val isWide = maxWidth > 600.dp

            if (state.isLoading && state.timelineEvents.isEmpty() && state.dailyTasks.isEmpty()) {
                 Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                 }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 1. Header & Stats
                    item {
                        val moodData = state.moodData
                        val lastUpdate = remember(moodData) { moodData?.entries?.firstOrNull()?.time }
                        DailyNoteHeader(
                            emoji = moodData?.summary?.mainEmoji,
                            lastUpdate = lastUpdate,
                            topDailyTags = state.topDailyTags
                        )
                    }

                    // 2. Morning Briefing
                    item {
                        MorningBriefingSection(
                            state = state.briefingState,
                            onToggle = { viewModel.toggleBriefing() }
                        )
                    }

                    // 3. Adaptive Content
                    if (isWide) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left Column: Timeline
                                Column(modifier = Modifier.weight(0.4f)) {
                                    TimelineHeader(onAdd = { showAddTimelineDialog = true })
                                    Spacer(Modifier.height(8.dp))
                                    TimelineList(state.timelineEvents)
                                }

                                // Right Column: Journal
                                Column(modifier = Modifier.weight(0.6f)) {
                                    JournalHeader(onAdd = { showAddTaskDialog = true })
                                    Spacer(Modifier.height(8.dp))
                                    TaskList(state.dailyTasks, onToggle = { id, done -> viewModel.toggleTask(id, done) })
                                }
                            }
                        }
                    } else {
                        // Mobile Layout
                        item {
                            TimelineHeader(onAdd = { showAddTimelineDialog = true })
                            Spacer(Modifier.height(8.dp))
                            TimelineList(state.timelineEvents)
                        }

                        item {
                            JournalHeader(onAdd = { showAddTaskDialog = true })
                            Spacer(Modifier.height(8.dp))
                            TaskList(state.dailyTasks, onToggle = { id, done -> viewModel.toggleTask(id, done) })
                        }
                    }
                }
            }
        }
    }

    if (showAddTimelineDialog) {
        AddTimelineDialog(
            onDismiss = { showAddTimelineDialog = false },
            onConfirm = { content, time ->
                viewModel.addTimelineEntry(content, time)
                showAddTimelineDialog = false
            }
        )
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onConfirm = { label ->
                viewModel.addTask(label)
                showAddTaskDialog = false
            }
        )
    }
}

// --- Components ---

@Composable
private fun TimelineHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🗓️ Timeline",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.AddCircle,
                contentDescription = "Add Event",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun JournalHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "📝 Journal",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.AddCircle,
                contentDescription = "Add Task",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TimelineList(events: List<TimelineEntryEntity>) {
    if (events.isEmpty()) {
        Text(
            text = "No events recorded yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            events.sortedBy { it.time }.forEach { event ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = event.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(
                        text = event.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskList(tasks: List<DailyTaskEntity>, onToggle: (String, Boolean) -> Unit) {
    if (tasks.isEmpty()) {
        Text(
            text = "All caught up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    } else {
        // Sorted: Done Last, then by time (nulls last), then Priority
        val sorted = remember(tasks) {
            tasks.sortedWith(
                compareBy<DailyTaskEntity> { it.isDone }
                    .thenBy { it.scheduledTime == null }
                    .thenBy { it.scheduledTime }
                    .thenByDescending { it.priority }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sorted.forEach { task ->
                TaskItem(task, onToggle)
            }
        }
    }
}

@Composable
private fun TaskItem(task: DailyTaskEntity, onToggle: (String, Boolean) -> Unit) {
    Surface(
        onClick = { onToggle(task.id, !task.isDone) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (task.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (task.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (task.scheduledTime != null) {
                    Text(
                        text = "at ${task.scheduledTime.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (task.priority > 1) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "‼️",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AddTimelineDialog(onDismiss: () -> Unit, onConfirm: (String, LocalTime) -> Unit) {
    var content by remember { mutableStateOf("") }
    // Ideally use a TimePicker, for simple implementation we default to Now and let user edit?
    // Or just simple text field for now? User requested a "Dialog tailored... e.g TimePicker".
    // Implementing a full TimePicker requires extra state.
    
    // Simplification: We defaults to current time, user can adjust?
    // Let's use current time.
    val now = LocalTime.now()
    var time by remember { mutableStateOf(now) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Moment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("What happened?") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Time: ${time.format(DateTimeFormatter.ofPattern("HH:mm"))} (Auto)")
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (content.isNotBlank()) onConfirm(content, time) 
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Task") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Goal / Task") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { 
                if (label.isNotBlank()) onConfirm(label) 
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
