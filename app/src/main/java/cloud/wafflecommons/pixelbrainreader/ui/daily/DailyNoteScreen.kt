package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianCalloutPlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianImagePlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianLinkPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyNoteScreen(
    onNavigateBack: () -> Unit,
    onEditClicked: (String) -> Unit,
    onCheckInClicked: () -> Unit,
    onOpenHabits: () -> Unit, 
    onNavigateToSettings: () -> Unit, // New Param
    isGlobalSyncing: Boolean = false, 
    viewModel: DailyNoteViewModel = hiltViewModel(),
    lifeOSViewModel: cloud.wafflecommons.pixelbrainreader.ui.lifeos.LifeOSViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val lifeOsState by lifeOSViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.date) {
        lifeOSViewModel.loadData(state.date)
    }

    LaunchedEffect(Unit) {
        lifeOSViewModel.reloadTrigger.collect {
             viewModel.refresh() 
        }
    }

    // 1. Extract Standard Metadata (Now from State)
    val metadata = state.metadata

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Cortex",
                subtitle = state.date.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
                actions = {
                     FilledTonalIconButton(
                         onClick = onNavigateToSettings,
                         colors = IconButtonDefaults.filledTonalIconButtonColors(
                             containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                             contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                         )
                     ) {
                         Icon(
                             imageVector = Icons.Outlined.Settings,
                             contentDescription = "Settings"
                         )
                     }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Secondary FAB: Mood Check-in
                SmallFloatingActionButton(
                    onClick = onCheckInClicked,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.Mood, contentDescription = "Add Mood")
                }

                // Primary FAB: Edit Text
                ExtendedFloatingActionButton(
                    onClick = { 
                         // Generate the expected file path for "Today" to pass to editor
                         val dateStr = state.date.format(DateTimeFormatter.ISO_DATE)
                         onEditClicked("10_Journal/$dateStr.md")
                    },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Edit Text") }
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.isLoading || isGlobalSyncing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // 1. TOP HEADER (Legacy: Date/Nav/Emoji)
                    item {
                        // Logic: Always show header, handle nulls gracefully
                        val moodData = state.moodData
                        val lastUpdate = remember(moodData) { moodData?.entries?.firstOrNull()?.time }
                        val allActivities = remember(moodData) { 
                            moodData?.entries?.flatMap { it.activities }?.distinct()?.sorted() ?: emptyList()
                        }

                        cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader(
                            emoji = moodData?.summary?.mainEmoji,
                            lastUpdate = lastUpdate,
                            topDailyTags = state.topDailyTags,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 2. MORNING BRIEFING 2.0 (Cockpit)
                    item {
                        cloud.wafflecommons.pixelbrainreader.ui.journal.MorningBriefingSection(
                            state = state.briefingState,
                            onToggle = { viewModel.toggleBriefing() },
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }

                    // 3. BODY (Timeline + Journal)
                    item {
                        // Intro Text
                        if (state.displayIntro.isNotBlank()) {
                             MarkdownText(
                                markdown = state.displayIntro,
                                onWikiLinkClick = { /* No-op */ }
                            )
                             Spacer(modifier = Modifier.height(24.dp))
                        }

                        // Split Layout
                        BoxWithConstraints {
                            val isWide = maxWidth > 600.dp
                            
                            if (isWide) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(modifier = Modifier.weight(0.4f)) {
                                         TimelineSection(events = state.timelineEvents)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(0.6f)
                                            .fillMaxWidth()
                                    ) {
                                         JournalSection(
                                             tasks = lifeOsState.scopedTasks,
                                             noteOutro = state.noteOutro,
                                             onToggle = { task -> 
                                                 viewModel.toggleTask(task)
                                                 // Trigger LifeOS refresh to update list UI
                                                 lifeOSViewModel.loadData(state.date)
                                             },
                                             modifier = Modifier.fillMaxWidth()
                                         )
                                    }
                                }
                            } else {
                                // Mobile Layout (Stacked)
                                Column {
                                     TimelineSection(events = state.timelineEvents)
                                     Spacer(modifier = Modifier.height(24.dp))
                                     JournalSection(
                                         tasks = lifeOsState.scopedTasks,
                                         noteOutro = "",
                                         onToggle = { task -> 
                                             viewModel.toggleTask(task)
                                             lifeOSViewModel.loadData(state.date)
                                         },
                                         modifier = Modifier.fillMaxWidth()
                                     )
                                }
                            }
                        }
                        // Outro Text
                        if (state.noteOutro.isNotBlank()) {
                            MarkdownText(
                                markdown = state.noteOutro,
                                onWikiLinkClick = { /* No-op */ }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- Extracted Components for Readability & Layout Reuse ---

@Composable
private fun TimelineSection(events: List<TimelineEvent>) {
    Column {
        Text(
            text = "🗓️ Timeline",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // No spacing here

        cloud.wafflecommons.pixelbrainreader.ui.lifeos.DayTimeline(
            events = events
        )
    }
}

@Composable
private fun JournalSection(
    tasks: List<Task>,
    noteOutro: String,
    onToggle: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "📝 Journal",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        cloud.wafflecommons.pixelbrainreader.ui.lifeos.TaskTimeline(
            tasks = tasks,
            onToggle = onToggle
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    onWikiLinkClick: (String) -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor)
                textSize = 16f
                setLineSpacing(12f, 1.1f)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                isNestedScrollingEnabled = false
            }
        },
        update = { tv ->
            val markwon = Markwon.builder(tv.context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(tv.context))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(TaskListPlugin.create(textColor, textColor, textColor))
                .usePlugin(ObsidianLinkPlugin { target -> onWikiLinkClick(target) })
                .usePlugin(ObsidianImagePlugin())
                .usePlugin(ObsidianCalloutPlugin())
                .usePlugin(ImagesPlugin.create())
                .usePlugin(io.noties.markwon.html.HtmlPlugin.create { plugin ->
                    plugin.addHandler(object : io.noties.markwon.html.tag.SimpleTagHandler() {
                        override fun supportedTags() = listOf("obsidian-image")
                        override fun getSpans(
                            configuration: io.noties.markwon.MarkwonConfiguration,
                            renderProps: io.noties.markwon.RenderProps,
                            tag: io.noties.markwon.html.HtmlTag
                        ): Any? {
                            return null
                        }
                    })
                })
                .build()
            
            markwon.setMarkdown(tv, markdown)
        }
    )
}

@Composable
fun MetadataView(tags: List<String>, date: String?, weather: String? = null, location: String? = null) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Date
        if (date != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = Icons.Default.CalendarToday, 
                    contentDescription = null, 
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = date, 
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Weather
        if (weather != null) {
             Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = weather.replace("\"", ""), // Strip quotes if present
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Tags Chips
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    SuggestionChip(
                        onClick = {}, 
                        label = { Text("#$tag") },
                        modifier = Modifier.height(26.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        border = null
                    )
                }
            }
        }
    }
}
