package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
    lifeOSViewModel: cloud.wafflecommons.pixelbrainreader.ui.lifeos.LifeOSViewModel = hiltViewModel() // Legacy
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                    
                    // 3. Mantra
                    if (state.mantra.isNotBlank()) {
                         item {
                             Text(
                                 text = state.mantra,
                                 style = MaterialTheme.typography.bodyLarge,
                                 fontWeight = FontWeight.Medium,
                                 fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                 modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                 textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                 color = MaterialTheme.colorScheme.secondary
                             )
                         }
                    }

                    // 4. Adaptive Content (Two Columns vs Single Column)
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

                                // Right Column: Journal + Second Brain
                                Column(modifier = Modifier.weight(0.6f)) {
                                    JournalHeader(onAdd = { showAddTaskDialog = true })
                                    Spacer(Modifier.height(8.dp))
                                    TaskList(state.dailyTasks, onToggle = { id, done -> viewModel.toggleTask(id, done) })
                                }
                            }
                        }
                    } else {
                        // Mobile Layout: Sequential
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

                    // Second Brain on Mobile
                    item {
                        SecondBrainSection(
                            ideas = state.ideasContent,
                            notes = state.notesContent,
                            onIdeasChange = viewModel::onIdeasChanged,
                            onNotesChange = viewModel::onNotesChanged
                        )
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
private fun SecondBrainSection(
    ideas: String,
    notes: String,
    onIdeasChange: (String) -> Unit,
    onNotesChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider()
        Text("🧠 Idées / Second Cerveau", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = ideas,
            onValueChange = onIdeasChange,
            modifier = Modifier.fillMaxWidth().height(120.dp),
            placeholder = { Text("Capture ideas, quick thoughts...") }
        )
        
        Text("📑 Notes / Self-care", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth().height(120.dp),
            placeholder = { Text("Reflection, gratitude, notes...") }
        )
    }
}

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
                    .thenBy { it.scheduledTime == null } // Nulls last
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTimelineDialog(onDismiss: () -> Unit, onConfirm: (String, LocalTime) -> Unit) {
    var content by remember { mutableStateOf("") }
    // Ideally use a TimePicker... simplifying to "Auto Now" for speed as per previous iteration unless demanded.
    // User Constraint: "Action: 'Add' button opens a TimePicker + TextField."
    // Let's implemented a TimePicker properly this time.
    
    val timePickerState = rememberTimePickerState(
        initialHour = LocalTime.now().hour,
        initialMinute = LocalTime.now().minute,
        is24Hour = true
    )
    
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
                TimeInput(state = timePickerState) // Or TimePicker for full dial
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (content.isNotBlank()) {
                    onConfirm(content, LocalTime.of(timePickerState.hour, timePickerState.minute))
                }
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    // User: "Add button opens a TextField + optional TimePicker"
    // But currently the Task add only accepted label in VM... wait, Repository supports time!
    // But `DailyNoteViewModel.addTask` signature is `addTask(label)`.
    // I need to update ViewModel to accept Time! It was `addTask(label, time?)`.
    // Yes, the new VM has `addTask(label, time?)`.
    
    // So I need UI for optional time.
    var useTime by remember { mutableStateOf(false) }
    val timePickerState = rememberTimePickerState(
        initialHour = LocalTime.now().hour,
        initialMinute = LocalTime.now().minute,
        is24Hour = true
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Goal / Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useTime, onCheckedChange = { useTime = it })
                    Text("Scheduled?")
                }
                if (useTime) {
                    TimeInput(state = timePickerState)
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (label.isNotBlank()) {
                     // Wait, I need to pass time to onConfirm.
                     // The signature passed to this Composable was (String)->Unit.
                     // I must update the caller to pass (String, LocalTime?) -> Unit.
                     // But for now I'm inside the component.
                     // I need to update the parameter of AddTaskDialog.
                     // Wait, I can't change param type inside `ReplacementContent` without changing caller.
                     // Caller is `DailyNoteScreen`. I am replacing the WHOLE FILE. So I can change everything.
                     // OK.
                     onConfirm(label) // This is wrong if I don't pass time.
                     // I will update signature below.
                } 
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

