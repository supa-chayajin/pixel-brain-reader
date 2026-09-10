package cloud.wafflecommons.pixelbrainreader.ui.main

import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.ui.journal.DailyNoteHeader
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodViewModel
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianCalloutPlugin
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianHelper
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianImagePlugin
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.time.LocalDate

/** Daily notes are named after their date (`2026-09-10.md`). */
private val DAILY_NOTE_FILE_NAME = Regex("\\d{4}-\\d{2}-\\d{2}\\.md")

/** How many recent editor emissions are remembered to recognize their delayed echo. */
private const val MAX_TRACKED_EDITS = 32

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
            .then(if (isExpandedScreen) Modifier.padding(start = 4.dp, end = 8.dp) else Modifier)
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
                            val isDailyNote = fileName?.matches(DAILY_NOTE_FILE_NAME) ?: false

                            // Load mood data if it's a daily note
                            LaunchedEffect(fileName) {
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

                            if (isEditing) {
                                if (isExpandedScreen) {
                                    // --- TABLETOP MODE: Split View ---
                                    Column(Modifier.fillMaxSize()) {
                                        // Editor fills the pane and is IME/nav-inset, so its bounded
                                        // height lets BasicTextField scroll to the caret natively.
                                        NoteEditor(
                                            fileKey = fileName,
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
                                        NoteEditor(
                                            fileKey = fileName,
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
                                // Parsed only for reading: while editing these ran on every keystroke.
                                val parsed = remember(content) { ObsidianHelper.parse(content) }
                                val displayContent = remember(content) { FrontmatterManager.stripFrontmatter(content) }
                                val scrollState = rememberSaveable(saver = ScrollState.Saver) {
                                    ScrollState(initial = 0)
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
                                        val moodData = moodState.moodData
                                        if (isDailyNote && moodData != null) {
                                            val topDailyTags = remember(moodData) {
                                                moodData.entries.flatMap { entry: MoodEntry -> entry.activities }
                                                    .groupingBy { it }
                                                    .eachCount()
                                                    .entries
                                                    .sortedByDescending { it.value }
                                                    .take(5)
                                                    .map { it.key }
                                            }
                                            val lastUpdate = remember(moodData) { moodData.entries.firstOrNull()?.time }

                                            DailyNoteHeader(
                                                emoji = moodData.summary.mainEmoji,
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
}

/**
 * The note editor with LOCAL text state: keystrokes update it synchronously, so fast typing can't
 * drop characters or make the caret jump while the ViewModel catches up. Content that changes
 * elsewhere (text appended from chat, a reload) is still adopted; the delayed echo of the editor's
 * own recent emissions is recognized by identity and ignored.
 */
@Composable
private fun NoteEditor(
    fileKey: String?,
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier
) {
    var editorValue by remember(fileKey) { mutableStateOf(TextFieldValue(content)) }
    val sentTexts = remember(fileKey) { ArrayDeque<String>() }

    if (content != editorValue.text && sentTexts.none { it === content }) {
        editorValue = TextFieldValue(content, TextRange(content.length))
        sentTexts.clear()
    }

    cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor(
        value = editorValue,
        onValueChange = { newValue ->
            val textChanged = newValue.text != editorValue.text
            editorValue = newValue
            if (textChanged) {
                sentTexts.addLast(newValue.text)
                while (sentTexts.size > MAX_TRACKED_EDITS) sentTexts.removeFirst()
                onContentChange(newValue.text)
            }
        },
        modifier = modifier
    )
}

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

/**
 * A fully configured Markwon, built once per call site and theme. It used to be rebuilt — every
 * plugin, the Prism grammars and a new image-loading executor — on EVERY recomposition. Link clicks
 * always reach the latest [onWikiLinkClick].
 */
@Composable
fun rememberMarkwon(onWikiLinkClick: (String) -> Unit): Markwon {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val currentOnWikiLinkClick by rememberUpdatedState(onWikiLinkClick)
    return remember(context, colorScheme) {
        val textColor = colorScheme.onSurface.toArgb()
        val defaultLinkResolver = io.noties.markwon.LinkResolverDef()
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TaskListPlugin.create(textColor, textColor, textColor))
            // Fenced-code syntax highlighting: vendored Prism4j grammars via the
            // manual locator (see PrismHelper); theme follows the surface luminance.
            .usePlugin(
                io.noties.markwon.syntax.SyntaxHighlightPlugin.create(
                    cloud.wafflecommons.pixelbrainreader.ui.utils.PrismHelper.prism,
                    cloud.wafflecommons.pixelbrainreader.ui.utils.CodeTheme.create(colorScheme)
                )
            )
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
                                currentOnWikiLinkClick(decodeSafely(link.removePrefix("$WIKI_LINK_SCHEME://")))
                            link.startsWith("#") -> Unit // in-note anchor: no navigation
                            link.contains("://") || link.startsWith("mailto:") || link.startsWith("tel:") ->
                                defaultLinkResolver.resolve(view, link) // external → browser
                            else ->
                                currentOnWikiLinkClick(decodeSafely(link).removePrefix("./")) // relative → vault
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
    }
}

@Composable
fun MarkwonContent(content: String, onWikiLinkClick: (String) -> Unit) {
    val markwon = rememberMarkwon(onWikiLinkClick)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    // Parse + render only when the text (or theme) changes, not on every recomposition.
    val rendered = remember(markwon, content) { markwon.toMarkdown(convertWikiLinks(content)) }

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                textSize = 16f
                setLineSpacing(12f, 1.1f)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                isNestedScrollingEnabled = false
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            if (tv.tag !== rendered) {
                markwon.setParsedMarkdown(tv, rendered)
                tv.tag = rendered
            }
        }
    )
}
