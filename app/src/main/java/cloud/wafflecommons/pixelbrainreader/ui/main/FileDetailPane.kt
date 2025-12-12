package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.LinkResolverDef // For custom link handling if needed, or just Linkify
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.spans.LinkSpan
import android.text.style.ClickableSpan
import android.view.View
import java.util.regex.Pattern
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianHelper
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.compose.ui.graphics.toArgb


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileDetailPane(
    content: String?,
    fileName: String? = null,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isFocusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    isExpandedScreen: Boolean,
    isEditing: Boolean,
    onToggleEditMode: () -> Unit,
    onSaveContent: (String) -> Unit,
    onContentChange: (String) -> Unit,
    hasUnsavedChanges: Boolean,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onWikiLinkClick: (String) -> Unit, // NEW CALLBACK
    onCreateNew: () -> Unit = {} // Added for Welcome Screen action
) {
    // --- LAYOUT LOGIC ---
    // The parent scaffold now handles the top bar. We just need to present the content.
    // We retain the container styling (rounded corners, border) for the detail pane look.

    val shape = if (isExpandedScreen) {
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    // In a ListDetailPaneScaffold, we want the detail pane to look like a distinct "card" or sheet 
    // when in expanded mode, matching the list pane's style if possible.
    // However, the prompt asked to "Remove extraneous Spacer or margins... Tops must align perfectly".
    // So we will simply fill the provided space, but apply the visual container if needed.
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if(isExpandedScreen) Modifier.padding(start = 12.dp, end = 12.dp) else Modifier) // small separation from list
            .clip(shape)
            .then(if (isExpandedScreen) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape) else Modifier),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // --- CONTEXTUAL HEADER ---
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
                        // Icon (Optional, maybe specific to file type) or Empty
                        
                        // Filename & Dirty Indicator
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

                        // We can add more actions here later (Save button, etc.)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            
            // --- CONTENT AREA ---
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
                    // 1. Parse Content
                    val parsed = remember(content) { ObsidianHelper.parse(content) }
                    val displayContent = if (isEditing) content else parsed.cleanContent
                    
                    // 2. Editor Mode (Full Screen BasicTextField)
                    if (isEditing) {
                        BasicTextField(
                            value = content,
                            onValueChange = onContentChange,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .imePadding(),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    } else {
                        // 3. Reader Mode (Hybrid Layout)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp) // Base padding for the whole column
                                .verticalScroll(rememberScrollState())
                                .imePadding()
                        ) {
                            // --- Native Metadata Header ---
                            // Check if we have visible metadata
                            // Ideally, we always show header if title is present OR tags present OR other metadata.
                            if (!parsed.metadata.isEmpty() || parsed.tags.isNotEmpty()) {
                                MetadataHeader(parsed.metadata, parsed.tags)
                            } else {
                                Spacer(Modifier.height(16.dp))
                            }
                            
                            // Divider (only if we showed something in header or if we want separation)
                            // The MetadataHeader itself has a surface, so maybe just a small spacer below it?
                            Spacer(Modifier.height(8.dp))

                            // --- Markdown Content ---
                            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                            val checkedColor = MaterialTheme.colorScheme.primary.toArgb()
                            val uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                            val density = LocalDensity.current

                            AndroidView(
                                factory = { ctx ->
                                    TextView(ctx).apply {
                                        setTextColor(textColor)
                                        textSize = 16f
                                        setLineSpacing(12f, 1.1f)
                                        setTextIsSelectable(true)
                                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                                        // CRITICAL: Disable nested scrolling so the Column handles it
                                        isNestedScrollingEnabled = false 
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 200.dp), // Bottom padding for scrolling
                                update = { tv ->
                                    val context = tv.context
                                    val markwon = Markwon.builder(context)
                                        .usePlugin(StrikethroughPlugin.create())
                                        .usePlugin(TablePlugin.create(context))
                                        .usePlugin(LinkifyPlugin.create()) 
                                        .usePlugin(TaskListPlugin.create(checkedColor, uncheckedColor, uncheckedColor))
// ... (Top of file stays same until Callout Plugin)

// --- UPDATED CALLOUT PLUGIN LOGIC ---
                                        .usePlugin(object : AbstractMarkwonPlugin() {
                                            override fun processMarkdown(markdown: String): String {
                                                 return markdown.replace(ObsidianHelper.WIKI_LINK_REGEX) { match ->
                                                    val target = match.groupValues[1]
                                                    val label = match.groupValues[2].takeIf { it.isNotEmpty() } ?: target
                                                    "[$label](pixelbrain://note/$target)"
                                                }
                                            }
                                            
                                            override fun afterSetText(textView: TextView) {
                                                val spannable = textView.text as? android.text.SpannableStringBuilder ?: return
                                                
                                                // 1. Find all LeadingMarginSpan (covers Quotes, Lists, etc.)
                                                val margins = spannable.getSpans(0, spannable.length, android.text.style.LeadingMarginSpan::class.java)
                                                
                                                for (span in margins) {
                                                    val start = spannable.getSpanStart(span)
                                                    val end = spannable.getSpanEnd(span)
                                                    
                                                    if (start == -1 || end == -1 || start >= end) continue
                                                    
                                                    val text = spannable.subSequence(start, end).toString()
                                                    // Regex: Start of content (ignoring initial whitespace) matches [!TYPE]
                                                    val regex = Regex("^\\s*\\[!(\\w+)\\]")
                                                    val match = regex.find(text)
                                                    
                                                    if (match != null) {
                                                        val type = match.groupValues[1].uppercase()
                                                        
                                                        // 3. Remove original span
                                                        spannable.removeSpan(span)
                                                        
                                                        // 4. Resolve Colors
                                                        val (bgColor, stripeColor) = when(type) {
                                                            "TIP", "GOAL", "SUCCESS", "DONE" -> 0xFFE8F5E9.toInt() to 0xFF4CAF50.toInt() // Green
                                                            "INFO", "NOTE", "FYI" -> 0xFFE3F2FD.toInt() to 0xFF2196F3.toInt() // Blue
                                                            "WARNING", "CAUTION", "ATTENTION", "IMPORTANT" -> 0xFFFFF3E0.toInt() to 0xFFFF9800.toInt() // Orange
                                                            "DANGER", "ERROR", "BUG", "FAIL", "JIRA" -> 0xFFFFEBEE.toInt() to 0xFFF44336.toInt() // Red
                                                            "EXAMPLE", "QUOTE" -> 0xFFF3E5F5.toInt() to 0xFF9C27B0.toInt() // Purple
                                                            else -> 0xFFF5F5F5.toInt() to 0xFF9E9E9E.toInt() // Grey
                                                        }
                                                        
                                                        // 5. Apply CalloutSpan (Background + Stripe)
                                                        spannable.setSpan(
                                                            CalloutSpan(bgColor, stripeColor), 
                                                            start, end, 
                                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                                        )
                                                        
                                                        // 6. Hide [!TYPE] text
                                                        val tagStart = start + match.range.first
                                                        val tagEnd = start + match.range.last + 1
                                                        
                                                        if (tagStart < tagEnd && tagEnd <= spannable.length) {
                                                            spannable.setSpan(
                                                                android.text.style.ForegroundColorSpan(android.graphics.Color.TRANSPARENT),
                                                                tagStart, tagEnd,
                                                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                                            )
                                                            spannable.setSpan(
                                                                android.text.style.RelativeSizeSpan(0.01f),
                                                                tagStart, tagEnd,
                                                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                                            )
                                                            
                                                            // Bold the title
                                                            val lineEnd = text.indexOf('\n')
                                                            val titleEndAbs = if (lineEnd != -1) start + lineEnd else end
                                                            if (tagEnd < titleEndAbs) {
                                                                spannable.setSpan(
                                                                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                                                    tagEnd, titleEndAbs,
                                                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        })
// ... (DetailPane logic continues) ...


                                        .usePlugin(object : AbstractMarkwonPlugin() {
                                            override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                                                builder.linkResolver { view, link ->
                                                    if (link.startsWith("pixelbrain://note/")) {
                                                        val target = link.removePrefix("pixelbrain://note/")
                                                        onWikiLinkClick(target)
                                                    } else {
                                                        try {
                                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                                            view.context.startActivity(intent)
                                                        } catch(e: Exception) {}
                                                    }
                                                }
                                            }
                                        })
                                        .build()
                                        
                                    markwon.setMarkdown(tv, displayContent)
                                }
                            )
                        }
                    }
                }
                else -> {
                    // Welcome / Zero State
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
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        Text(
            "Ready to work",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        Text(
            "Select a file from the list or create a new one to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
        Button(onClick = onCreateNew) {
            Icon(Icons.Default.Add, null)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
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
            // TITLE
            val title = metadata["title"]
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // OTHER METADATA GRID
            // Filter out title and internal numeric keys if any
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
            
            // TAGS
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




// --- CUSTOM SPANS ---

class CalloutSpan(
    private val backgroundColor: Int,
    private val stripeColor: Int,
    private val stripeWidth: Int = 15,
    private val gap: Int = 30
) : android.text.style.LeadingMarginSpan, android.text.style.LineBackgroundSpan {

    override fun getLeadingMargin(first: Boolean): Int {
        return stripeWidth + gap
    }

    override fun drawLeadingMargin(
        c: android.graphics.Canvas,
        p: android.graphics.Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence?,
        start: Int,
        end: Int,
        first: Boolean,
        layout: android.text.Layout?
    ) {
        val originalStyle = p.style
        val originalColor = p.color

        p.style = android.graphics.Paint.Style.FILL
        p.color = stripeColor

        // Draw Stripe logic
        // x is the start of the paragraph.
        // dir is direction (1 for LTR).
        
        val stripeLeft = x.toFloat()
        val stripeRight = stripeLeft + stripeWidth * dir
        
        c.drawRect(
            if (dir > 0) stripeLeft else stripeRight,
            top.toFloat(),
            if (dir > 0) stripeRight else stripeLeft,
            bottom.toFloat(),
            p
        )

        p.style = originalStyle
        p.color = originalColor
    }

    override fun drawBackground(
        c: android.graphics.Canvas,
        p: android.graphics.Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        ln: Int
    ) {
        val originalColor = p.color
        p.color = backgroundColor
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
        p.color = originalColor
    }
}
