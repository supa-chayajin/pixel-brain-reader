package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Settings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianCalloutPlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianImagePlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianLinkPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
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
            TopAppBar(
                title = {
                    Text(
                        text = "Today, ${state.date.format(DateTimeFormatter.ofPattern("MMM dd"))}",
                    )
                },
                 actions = {
                     IconButton(onClick = onNavigateToSettings) {
                         Icon(
                             imageVector = Icons.Default.Settings,
                             contentDescription = "Settings",
                             tint = MaterialTheme.colorScheme.onSurfaceVariant
                         )
                     }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    // Header
                    val moodData = state.moodData
                    if (moodData != null) {
                         val allActivities = remember(moodData) { 
                            moodData.entries.flatMap { entry: MoodEntry -> entry.activities }
                                .distinct()
                                .sorted() 
                        }
                        val lastUpdate = remember(moodData) { moodData.entries.firstOrNull()?.time }

                        DailyNoteHeader(
                            emoji = moodData.summary.mainEmoji,
                            lastUpdate = lastUpdate,
                            activities = allActivities,
                            weather = state.weatherData,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    } else {
                        // Empty State / CTA for Mood
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mood,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Start your day with a check-in",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            androidx.compose.material3.Button(onClick = onCheckInClicked) {
                                Text("Check-in")
                            }
                        }
                    }

                    // Body
                    // 1. Introduction (Read Only)
                    if (state.noteIntro.isNotBlank()) {
                        MarkdownText(
                            markdown = state.noteIntro,
                            onWikiLinkClick = { /* No-op for now */ }
                        )
                    } else if (state.noteIntro.isBlank() && state.noteOutro.isBlank()) {
                         // Empty Note State if BOTH are blank
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No notes yet...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // --- Scoped Tasks Section (LifeOS) ---
                    // Always display this section between Intro and Outro
                    // Mimic Markdown H2 Style
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "📝 Journal",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 0.dp, vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    cloud.wafflecommons.pixelbrainreader.ui.lifeos.TaskTimeline(
                        tasks = lifeOsState.scopedTasks,
                        onToggle = { 
                            lifeOSViewModel.toggleTask(it)
                            viewModel.refresh()
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // --- End Scoped Tasks Section ---

                     // 2. Outro (Read Only)
                    if (state.noteOutro.isNotBlank()) {
                        MarkdownText(
                            markdown = state.noteOutro,
                            onWikiLinkClick = { /* No-op */ }
                        )
                    }
                    
                    // Bottom padding for FAB
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
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
