package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.components.MarkdownVisualTransformation
import cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader
import cloud.wafflecommons.pixelbrainreader.ui.journal.MorningBriefingSection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.sp

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
    var showQuickCaptureSheet by remember { mutableStateOf(false) }

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
                navigationIcon = {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp),
                            color = MaterialTheme.colorScheme.error,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.triggerEmergencySync() }) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Force Push (Iron Vault Override)",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
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

                    FilledTonalIconButton(
                        onClick = { showQuickCaptureSheet = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = "Quick Capture",
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 8.dp),
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

                    // 5. Second Brain Section
                    item {
                        SecondBrainSection(
                            ideas = state.ideasContent,
                            notes = state.notesContent,
                            onIdeasChange = viewModel::onIdeasChanged,
                            onNotesChange = viewModel::onNotesChanged
                        )
                    }

                    // 6. Scratchpad (New Module)
                    if (state.scratchNotes.isNotEmpty()) {
                        item {
                            ScratchpadWidget(
                                scraps = state.scratchNotes,
                                onDelete = { viewModel.deleteScrap(it) },
                                onPromote = { viewModel.promoteScrapToIdeas(it) }
                            )
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
            onConfirm = { label, time ->
                viewModel.addTask(label, time)
                showAddTaskDialog = false
            }
        )
    }

    if (showQuickCaptureSheet) {
        QuickCaptureSheet(
            onDismiss = { showQuickCaptureSheet = false },
            onSave = { content, color ->
                viewModel.saveScrap(content, color)
                showQuickCaptureSheet = false
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
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    val visualTransformation = remember(textColor, primaryColor) {
        MarkdownVisualTransformation(textColor, primaryColor, codeBackground)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        
        // Ideas
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "🧠 Idées / Second Cerveau",
                style = MaterialTheme.typography.titleMedium,
                color = secondaryColor,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = ideas,
                onValueChange = onIdeasChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("Capture lightning ideas...", color = textColor.copy(alpha = 0.4f)) },
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 26.sp,
                    color = textColor
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.medium
            )
        }
        
        // Notes
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "📑 Notes / Self-care",
                style = MaterialTheme.typography.titleMedium,
                color = secondaryColor,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("Reflection, gratitude, logs...", color = textColor.copy(alpha = 0.4f)) },
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 26.sp,
                    color = textColor
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = secondaryColor.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.medium
            )
        }
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
            color = MaterialTheme.colorScheme.secondary,
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
            color = MaterialTheme.colorScheme.secondary,
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
        Column(modifier = Modifier.padding(start = 8.dp)) {
            val sortedEvents = events.sortedBy { it.time }
            sortedEvents.forEachIndexed { index, event ->
                TimelineItem(
                    event = event,
                    isLast = index == sortedEvents.lastIndex
                )
            }
        }
    }
}

@Composable
private fun TimelineItem(event: TimelineEntryEntity, isLast: Boolean) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // Time Column & Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            Text(
                text = event.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            // Dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
            
            // Line
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Content
        Text(
            text = event.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 24.dp) // Spacing for next item
        )
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
        // IRON SORTING:
        // 1. Incomplete before Complete
        // 2. Timed tasks (ASC) before No-time tasks
        // 3. No-time tasks at the bottom
        val sorted = remember(tasks) {
            tasks.sortedWith(
                compareBy<DailyTaskEntity> { it.isDone }
                    .thenBy { it.scheduledTime == null } // False (has time) < True (null) -> Timed first
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.scheduledTime != null) {
                        Text(
                            text = task.scheduledTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    Text(
                        text = task.label,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
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
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String, LocalTime?) -> Unit) {
    var label by remember { mutableStateOf("") }
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
                
                // Time Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useTime = !useTime }
                ) {
                    Checkbox(checked = useTime, onCheckedChange = { useTime = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Scheduled Time?")
                }
                
                // Visible Time Input
                AnimatedVisibility(visible = useTime) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TimeInput(state = timePickerState)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (label.isNotBlank()) {
                     val time = if (useTime) LocalTime.of(timePickerState.hour, timePickerState.minute) else null
                     onConfirm(label, time)
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
private fun QuickCaptureSheet(
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var content by remember { mutableStateOf("") }
    val colors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        Color(0xFFFFB4AB), // Pastel Red
        Color(0xFFC2E7FF), // Pastel Blue
        Color(0xFFD3EBCD), // Pastel Green
        Color(0xFFF3E5F5)  // Pastel Purple
    )
    var selectedColor by remember { mutableStateOf(colors[0]) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Quick Capture",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("What's on your mind?", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = selectedColor.copy(alpha = 0.1f),
                    unfocusedContainerColor = selectedColor.copy(alpha = 0.05f)
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Color Selectors
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(color, androidx.compose.foundation.shape.CircleShape)
                                .clickable { selectedColor = color }
                                .let { if (selectedColor == color) it.border(2.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape) else it }
                        )
                    }
                }

                Button(
                    onClick = { if (content.isNotBlank()) onSave(content, selectedColor.toArgb()) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Scrap")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScratchpadWidget(
    scraps: List<ScratchNoteEntity>,
    onDelete: (ScratchNoteEntity) -> Unit,
    onPromote: (ScratchNoteEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "💡 Scratchpad",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(scraps, key = { it.id }) { scrap ->
                ScratchItem(scrap, onDelete, onPromote)
            }
        }
    }
}

@Composable
private fun ScratchItem(
    scrap: ScratchNoteEntity,
    onDelete: (ScratchNoteEntity) -> Unit,
    onPromote: (ScratchNoteEntity) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(220.dp)
            .heightIn(min = 100.dp, max = 160.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(scrap.color).copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(scrap.color).copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = scrap.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onDelete(scrap) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { onPromote(scrap) }) {
                    Icon(
                        imageVector = Icons.Default.Upgrade,
                        contentDescription = "Promote to Ideas",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

