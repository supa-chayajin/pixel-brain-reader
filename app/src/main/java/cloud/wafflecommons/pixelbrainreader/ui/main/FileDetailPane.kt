package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodViewModel
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianHelper
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianLinkPlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianImagePlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianCalloutPlugin
import androidx.hilt.navigation.compose.hiltViewModel
import io.noties.markwon.image.ImagesPlugin
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.time.LocalDate
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileDetailPane(
    content: String?,
    fileName: String? = null,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isExpandedScreen: Boolean,
    isEditing: Boolean,
    onContentChange: (String) -> Unit,
    hasUnsavedChanges: Boolean,
    onWikiLinkClick: (String) -> Unit,
    onCreateNew: () -> Unit = {},
    moodViewModel: MoodViewModel = hiltViewModel()
) {
    val shape = if (isExpandedScreen) {
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if(isExpandedScreen) Modifier.padding(start = 12.dp, end = 12.dp) else Modifier)
            .clip(shape)
            .then(if (isExpandedScreen) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape) else Modifier),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (fileName != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            if (hasUnsavedChanges) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            
            cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    isLoading && content == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    content != null -> {
                        val parsed = remember(content) { ObsidianHelper.parse(content) }
                        val isDailyNote = fileName?.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) ?: false
                        
                        // Load mood data if it's a daily note
                        androidx.compose.runtime.LaunchedEffect(fileName) {
                            if (isDailyNote && fileName != null) {
                                try {
                                    val dateStr = fileName.substringBefore(".md")
                                    val date = LocalDate.parse(dateStr)
                                    moodViewModel.loadMood(date)
                                } catch (e: Exception) {
                                    // Ignore parse errors for non-conforming files
                                }
                            }
                        }

                        val moodState by moodViewModel.uiState.collectAsState()

                        val displayContent = remember(content, isEditing) {
                            if (isEditing) {
                                content
                            } else {
                                FrontmatterManager.stripFrontmatter(content)
                            }
                        }
                        
                        if (isEditing) {
                            BasicTextField(
                                value = content,
                                onValueChange = onContentChange,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (isDailyNote && moodState.moodData != null) {
                                    val data = moodState.moodData!!
                                    val allActivities = remember(data) { 
                                        data.entries.flatMap { entry: MoodEntry -> entry.activities }
                                            .distinct()
                                            .sorted() 
                                    }
                                    val lastUpdate = remember(data) { data.entries.firstOrNull()?.time }

                                    DailyNoteHeader(
                                        emoji = data.summary.mainEmoji,
                                        lastUpdate = lastUpdate,
                                        activities = allActivities,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }

                                if (parsed.metadata.isNotEmpty() || parsed.tags.isNotEmpty()) {
                                    MetadataHeader(parsed.metadata, parsed.tags)
                                } else if (!isDailyNote) {
                                    Spacer(Modifier.height(16.dp))
                                }
                                
                                Spacer(Modifier.height(8.dp))

                                MarkwonContent(content = displayContent, onWikiLinkClick = onWikiLinkClick)
                            }
                        }
                    }
                    else -> {
                        WelcomeState(onCreateNew = onCreateNew)
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeState(onCreateNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Article, 
            contentDescription = null, 
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primaryContainer
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Ready to work",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Select a file from the list or create a new one to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCreateNew) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Create new file")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataHeader(metadata: Map<String, String>, tags: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val title = metadata["title"]
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            val displayMetadata = metadata.filterKeys { it != "title" && it != "tags" }
            if (displayMetadata.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    displayMetadata.forEach { (key, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$key: ",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurface 
                            )
                        }
                    }
                }
            }
            
            if (tags.isNotEmpty()) {
                if (displayMetadata.isNotEmpty() || !title.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text("#$tag") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surface, 
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = null,
                            shape = CircleShape
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun MarkwonContent(content: String, onWikiLinkClick: (String) -> Unit) {
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
                // --- HTML PLUGIN FOR FALLBACKS ---
                .usePlugin(io.noties.markwon.html.HtmlPlugin.create { plugin ->
                    plugin.addHandler(object : io.noties.markwon.html.tag.SimpleTagHandler() {
                        override fun supportedTags() = listOf("obsidian-image")
                        override fun getSpans(
                            configuration: io.noties.markwon.MarkwonConfiguration,
                            renderProps: io.noties.markwon.RenderProps,
                            tag: io.noties.markwon.html.HtmlTag
                        ): Any? {
                            // Placeholder for now, images will be handled later
                            return null
                        }
                    })
                })
                .build()
            
            markwon.setMarkdown(tv, content)
        }
    )
}
