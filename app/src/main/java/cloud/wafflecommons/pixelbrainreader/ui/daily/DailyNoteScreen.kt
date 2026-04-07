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
import androidx.compose.material.icons.filled.Archive
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
import androidx.compose.material.icons.filled.Lock
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
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexExpandableFAB
import cloud.wafflecommons.pixelbrainreader.ui.components.FabActionItem
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource

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
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val gamificationState by viewModel.gamificationState.collectAsStateWithLifecycle()
    val oracleInsight by viewModel.oracleInsight.collectAsStateWithLifecycle()
    val gratitudes by viewModel.gratitudes.collectAsStateWithLifecycle() // RFC-009
    val isOracleExpanded by viewModel.isOracleExpanded.collectAsStateWithLifecycle()

    var showAddTimelineDialog by remember { mutableStateOf(false) }
    var editTimelineEntry by remember { mutableStateOf<TimelineEntryEntity?>(null) }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var editTaskEntry by remember { mutableStateOf<DailyTaskEntity?>(null) }
    var showQuickCaptureSheet by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

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
                    FilledTonalIconButton(
                        onClick = { viewModel.compileDay() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = "Compile/Burn Day"
                        )
                    }

                    if (state.isLoading) {
                        cloud.wafflecommons.pixelbrainreader.ui.components.SaveStatusIndicator(
                            state = saveState,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { lifeOSViewModel.forceSyncEverything() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }

                    val context = androidx.compose.ui.platform.LocalContext.current
                    FilledTonalIconButton(
                        onClick = {
                            val intent = android.content.Intent(context, cloud.wafflecommons.pixelbrainreader.ui.privatevault.PrivateJournalActivity::class.java)
                            context.startActivity(intent)
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Private Vault",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
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
            val fabItems = listOf(
                FabActionItem(icon = Icons.Default.Mood, label = "Mood Check-in") {
                    fabExpanded = false
                    onCheckInClicked()
                },
                FabActionItem(icon = Icons.Default.AddCircle, label = "Timeline Item") {
                    fabExpanded = false
                    showAddTimelineDialog = true
                },
                FabActionItem(icon = Icons.Default.CheckCircle, label = "Fast Task") {
                    fabExpanded = false
                    showAddTaskDialog = true
                },
                FabActionItem(icon = Icons.Default.Lightbulb, label = "Scratchpad") {
                    fabExpanded = false
                    showQuickCaptureSheet = true
                }
            )

            CortexExpandableFAB(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                items = fabItems
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .fillMaxSize()
            ) {
                val isWide = maxWidth > 600.dp

            if (state.isLoading && state.timelineEvents.isEmpty() && state.dailyTasks.isEmpty()) {
                 DailyNoteSkeleton()
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
                        cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 0) {
                            val moodData = state.moodData
                            val lastUpdate = remember(moodData) { moodData?.entries?.firstOrNull()?.time }
                                DailyNoteHeader(
                                emoji = moodData?.summary?.mainEmoji,
                                lastUpdate = lastUpdate,
                                topDailyTags = state.topDailyTags,
                                healthMetrics = state.healthMetrics,
                                oracleInsight = oracleInsight,
                                isOracleExpanded = isOracleExpanded,
                                onToggleOracle = viewModel::toggleOracleExpanded
                            )
                        }
                    }

                    // 1.5 Hero Card (Gamification)
                    if (gamificationState != null) {
                        item {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 1) {
                                cloud.wafflecommons.pixelbrainreader.ui.gamification.HeroCard(
                                    state = gamificationState!!,
                                    isHealthSynergyActive = false, // Handled via LifeStatsScreen if placed there, or we can pipe from LifeStatsViewModel
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 2. Morning Briefing
                    item {
                        cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 2) {
                            MorningBriefingSection(
                                state = state.briefingState,
                                onToggle = { viewModel.toggleBriefing() }
                            )
                        }
                    }
                    
                    // 3. Mantra
                    if (state.mantra.isNotBlank()) {
                         item {
                             cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 3) {
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
                    }

                    // 4. Gratitude Express (RFC-009)
                    item {
                        cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 4) {
                            GratitudeSection(
                                gratitudes = gratitudes,
                                onAddGratitude = viewModel::addGratitude,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                    }

                    // 5. Adaptive Content (Two Columns vs Single Column)
                    if (isWide) {
                        item {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 5) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Left Column: Timeline
                                    Column(modifier = Modifier.weight(0.4f)) {
                                        TimelineHeader()
                                        Spacer(Modifier.height(8.dp))
                                        TimelineList(
                                            events = state.timelineEvents, 
                                            onEdit = { editTimelineEntry = it }
                                        )
                                    }

                                    // Right Column: Journal + Second Brain
                                    Column(modifier = Modifier.weight(0.6f)) {
                                        JournalHeader()
                                        Spacer(Modifier.height(8.dp))
                                        TaskList(
                                            tasks = state.dailyTasks, 
                                            onToggle = { id, done -> viewModel.toggleTask(id, done) },
                                            onEdit = { editTaskEntry = it }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 5. Timeline Header (Static)
                        item {
                            TimelineHeader()
                            Spacer(Modifier.height(8.dp))
                        }
                        
                        // 5b. Timeline List (Animated)
                        item {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 5) {
                                TimelineList(
                                    events = state.timelineEvents,
                                    onEdit = { editTimelineEntry = it }
                                )
                            }
                        }

                        // 6. Journal Header (Static)
                        item {
                            JournalHeader()
                            Spacer(Modifier.height(8.dp))
                        }

                        // 6b. Journal List (Animated)
                        item {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 6) {
                                TaskList(
                                    tasks = state.dailyTasks, 
                                    onToggle = { id, done -> viewModel.toggleTask(id, done) },
                                    onEdit = { editTaskEntry = it }
                                )
                            }
                        }
                    }

                    // 6. Second Brain Section
                    item {
                        cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 7) {
                            SecondBrainSection(
                                ideas = state.ideasContent,
                                notes = state.notesContent,
                                onIdeasChange = viewModel::onIdeasChanged,
                                onNotesChange = viewModel::onNotesChanged
                            )
                        }
                    }

                    // 7. Scratchpad (New Module)
                    if (state.scratchNotes.isNotEmpty()) {
                        item {
                            cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry(index = 8) {
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
            
            // Backdrop Overlay
            AnimatedVisibility(
                visible = fabExpanded,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { fabExpanded = false }
                )
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
            onConfirm = { label, date, time ->
                viewModel.addTask(label, date, time)
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

    if (editTimelineEntry != null) {
        val entryToEdit = editTimelineEntry!!
        EditTimelineDialog(
            initialContent = entryToEdit.content,
            initialTime = entryToEdit.time,
            onDismiss = { editTimelineEntry = null },
            onConfirm = { newContent, newTime ->
                viewModel.updateTimelineEntry(entryToEdit.copy(content = newContent, time = newTime))
                editTimelineEntry = null
            }
        )
    }

    if (editTaskEntry != null) {
        val taskToEdit = editTaskEntry!!
        val scheduledTime = taskToEdit.scheduledTime?.let {
            LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm"))
        }

        EditTaskDialog(
            initialLabel = taskToEdit.label,
            initialTime = scheduledTime,
            onDismiss = { editTaskEntry = null },
            onConfirm = { newLabel, newTime ->
                val newTimeStr = newTime?.format(DateTimeFormatter.ofPattern("HH:mm"))
                viewModel.updateTask(taskToEdit.copy(label = newLabel, scheduledTime = newTimeStr))
                editTaskEntry = null
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
private fun TimelineHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), /* Added padding just in case */
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🗓️ Timeline",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f) // Fix for Folded Mode
        )
    }
}

@Composable
private fun JournalHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "📝 Tasks",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f) // Fix for Folded Mode
        )
    }
}

@Composable
private fun TimelineList(events: List<TimelineEntryEntity>, onEdit: (TimelineEntryEntity) -> Unit) {
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
                    isLast = index == sortedEvents.lastIndex,
                    onClick = { onEdit(event) }
                )
            }
        }
    }
}

@Composable
private fun TimelineItem(event: TimelineEntryEntity, isLast: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.height(IntrinsicSize.Min).clickable { onClick() }.padding(vertical = 4.dp)) {
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
private fun TaskList(tasks: List<DailyTaskEntity>, onToggle: (String, Boolean) -> Unit, onEdit: (DailyTaskEntity) -> Unit) {
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
                TaskItem(task, onToggle, onEdit)
            }
        }
    }
}

@Composable
private fun TaskItem(task: DailyTaskEntity, onToggle: (String, Boolean) -> Unit, onEdit: (DailyTaskEntity) -> Unit) {
    Surface(
        onClick = { onEdit(task) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onToggle(task.id, !task.isDone) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (task.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (task.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String, java.time.LocalDate, LocalTime?) -> Unit) {
    var label by remember { mutableStateOf("") }
    var useTime by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM dd")

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
                
                // Date Picker
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.fillMaxWidth().clickable {
                        android.app.DatePickerDialog(
                            context,
                            { _, y, m, d -> selectedDate = java.time.LocalDate.of(y, m + 1, d) },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth
                        ).show()
                    }
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedDate == java.time.LocalDate.now()) "Today" else selectedDate.format(dateFormatter),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Time Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useTime = !useTime }
                ) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.CortexBouncyCheckbox(
                        checked = useTime, 
                        onCheckedChange = { useTime = it }
                    )
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
                     onConfirm(label, selectedDate, time)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTimelineDialog(
    initialContent: String, 
    initialTime: LocalTime,
    onDismiss: () -> Unit, 
    onConfirm: (String, LocalTime) -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }
    
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Moment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("What happened?") },
                    modifier = Modifier.fillMaxWidth()
                )
                TimeInput(state = timePickerState)
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (content.isNotBlank()) {
                    onConfirm(content, LocalTime.of(timePickerState.hour, timePickerState.minute))
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskDialog(
    initialLabel: String, 
    initialTime: LocalTime?, 
    onDismiss: () -> Unit, 
    onConfirm: (String, LocalTime?) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var useTime by remember { mutableStateOf(initialTime != null) }
    
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime?.hour ?: LocalTime.now().hour,
        initialMinute = initialTime?.minute ?: LocalTime.now().minute,
        is24Hour = true
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Goal / Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useTime = !useTime }
                ) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.CortexBouncyCheckbox(
                        checked = useTime, 
                        onCheckedChange = { useTime = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scheduled Time?")
                }
                
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

