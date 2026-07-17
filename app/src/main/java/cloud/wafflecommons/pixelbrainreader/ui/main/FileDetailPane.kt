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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileDetailPane(
    content: String?,
    fileName: String? = null,
    isLoading: Boolean,
    isRefreshing: Boolean,
    statusText: String? = null,
    onRefresh: () -> Unit,
    isExpandedScreen: Boolean,
    isEditing: Boolean,
    onContentChange: (String) -> Unit,
    saveState: cloud.wafflecommons.pixelbrainreader.ui.components.SaveState,
    hasUnsavedChanges: Boolean,
    onWikiLinkClick: (String) -> Unit,
    onCreateNew: () -> Unit = {},
    onSave: () -> Unit = {},
    moodViewModel: MoodViewModel = hiltViewModel()
) {
    // ... (Shape and Surface logic remains) ...

    val lifecycleOwner = LocalLifecycleOwner.current
    // The DisposableEffect below runs ONCE (its key, lifecycleOwner, is stable), so the
    // observer closes over whatever `hasUnsavedChanges`/`onSave` were at first composition —
    // which is always "no unsaved changes" right after a file opens. rememberUpdatedState
    // keeps the observer reading the LIVE values, so the ON_PAUSE/ON_STOP flush actually
    // fires after the user edits (the whole point of this safety net).
    val currentHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val currentOnSave by rememberUpdatedState(onSave)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (currentHasUnsavedChanges) {
                    currentOnSave() // Flushes auto-save immediately before death/background
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shape = if (isExpandedScreen) {
        MaterialTheme.shapes.large
    } else {
        RoundedCornerShape(0.dp)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if(isExpandedScreen) Modifier.padding(start = 4.dp, end = 8.dp) else Modifier)
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
                        }
                        Spacer(Modifier.weight(1f))
                        cloud.wafflecommons.pixelbrainreader.ui.components.SaveStatusIndicator(
                            state = saveState,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
            
            cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                statusText = statusText,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.fillMaxSize()) { // Wrap for FAB
                    when {
                    isLoading && content == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                    content != null -> {
                        val parsed = remember(content) { ObsidianHelper.parse(content) }
                        val isDailyNote = fileName?.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) ?: false
                        
                        // Load mood data if it's a daily note
                        androidx.compose.runtime.LaunchedEffect(fileName) {
                            if (isDailyNote) {
                                try {
                                    val dateStr = fileName.substringBefore(".md")
                                    val date = LocalDate.parse(dateStr)
                                    moodViewModel.loadMood(date)
                                } catch (e: Exception) {
                                    // Ignore parse errors for non-conforming files
                                }
                            }
                        }

                        val moodState by moodViewModel.uiState.collectAsStateWithLifecycle()

                        val displayContent = remember(content, isEditing) {
                            if (isEditing) {
                                content
                            } else {
                                FrontmatterManager.stripFrontmatter(content)
                            }
                        }
                        
                        if (isEditing) {
                            if (isExpandedScreen) {
                                // --- TABLETOP MODE: Split View ---
                                Column(Modifier.fillMaxSize()) {
                                    // Editor fills the pane and is IME/nav-inset, so its bounded
                                    // height lets BasicTextField scroll to the caret natively.
                                    cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor(
                                        content = content,
                                        onContentChange = onContentChange,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                                            .padding(16.dp)
                                    )
                                }
                            } else {
                                // --- STANDARD MODE: Reactive & Optimized ---
                                Column(Modifier.fillMaxSize()) {
                                    // Editor fills the pane and is IME/nav-inset, so its bounded
                                    // height lets BasicTextField scroll to the caret natively.
                                    cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor(
                                        content = content,
                                        onContentChange = onContentChange,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        } else {
                            val scrollState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
                                androidx.compose.foundation.ScrollState(initial = 0)
                            }
                            
                            // View Mode also benefits from the clean slate structure for consistency
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .navigationBarsPadding()
                                    .imePadding()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp)
                                        .verticalScroll(scrollState)
                                ) {
                                if (isDailyNote && moodState.moodData != null) {
                                    val data = moodState.moodData!!
                                    val topDailyTags = remember(data) { 
                                        data.entries.flatMap { entry: MoodEntry -> entry.activities }
                                            .groupingBy { it }
                                            .eachCount()
                                            .entries
                                            .sortedByDescending { it.value }
                                            .take(5)
                                            .map { it.key }
                                    }
                                    val lastUpdate = remember(data) { data.entries.firstOrNull()?.time }

                                    DailyNoteHeader(
                                        emoji = data.summary.mainEmoji,
                                        lastUpdate = lastUpdate,
                                        topDailyTags = topDailyTags,
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

                                // Reserve bottom space for the floating ExpressiveNavBar, which
                                // overlays the content and reserves no layout space. Without this
                                // the last markdown lines scroll under the bar and are unreadable.
                                Spacer(Modifier.height(ExpressiveNavBarClearance))
                            }
                            // End View Mode Box
                        }
                    }

            } // End content != null block

            else -> {
                WelcomeState(onCreateNew = onCreateNew)
            }
        } // End when
    } // End Box (FAB wrapper)
    } // End PullToRefreshBox
    } // End Column
    } // End Surface
} // End FileDetailPane function

@Composable
fun WelcomeState(onCreateNew: () -> Unit) {
    val haptic = LocalHapticFeedback.current
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
        Button(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCreateNew()
        }) {
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
        shape = MaterialTheme.shapes.small,
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


/** URI scheme used internally to carry an Obsidian wikilink target through Markwon's
 *  markdown-link machinery so clicks route to [MarkwonContent]'s LinkResolver. */
private const val WIKI_LINK_SCHEME = "pixelbrain-wiki"

// [[target]] / [[target|label]] → a standard markdown link carrying WIKI_LINK_SCHEME.
// This makes Markwon render just the label (no raw brackets) AND makes the region a real
// clickable link. The negative lookbehind keeps image embeds (![[image.png]]) untouched.
private val WIKI_LINK_REGEX = Regex("(?<!!)\\[\\[([^\\]|]+?)(?:\\|([^\\]]+))?\\]\\]")

private fun convertWikiLinks(markdown: String): String =
    WIKI_LINK_REGEX.replace(markdown) { m ->
        val target = m.groupValues[1].trim()
        val label = m.groupValues[2].trim().ifEmpty { target }
        val encoded = java.net.URLEncoder.encode(target, "UTF-8")
        "[$label]($WIKI_LINK_SCHEME://$encoded)"
    }

private fun decodeSafely(value: String): String =
    try { java.net.URLDecoder.decode(value, "UTF-8") } catch (e: Exception) { value }

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
            val defaultLinkResolver = io.noties.markwon.LinkResolverDef()
            val markwon = Markwon.builder(tv.context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(tv.context))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(TaskListPlugin.create(textColor, textColor, textColor))
                .usePlugin(ObsidianImagePlugin())
                .usePlugin(ObsidianCalloutPlugin())
                .usePlugin(ImagesPlugin.create())
                // Route every link click: internal wikilinks / relative note paths open
                // in-app; http(s)/mailto/tel fall through to the default resolver, which
                // fires ACTION_VIEW and opens the URL in the browser (Chrome).
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                        builder.linkResolver { view, link ->
                            when {
                                link.startsWith("$WIKI_LINK_SCHEME://") ->
                                    onWikiLinkClick(decodeSafely(link.removePrefix("$WIKI_LINK_SCHEME://")))
                                link.startsWith("#") -> Unit // in-note anchor: no navigation
                                link.contains("://") || link.startsWith("mailto:") || link.startsWith("tel:") ->
                                    defaultLinkResolver.resolve(view, link) // external → browser
                                else ->
                                    onWikiLinkClick(decodeSafely(link).removePrefix("./")) // relative → vault
                            }
                        }
                    }
                })
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

            markwon.setMarkdown(tv, convertWikiLinks(content))
        }
    )
}
