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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

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
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isLoading && content == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                content != null -> {
                    // Editor / Reader
                    if (isEditing) {
                        // Edit Mode
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
                        // View Mode
                        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                        val primaryColorInt = MaterialTheme.colorScheme.primary.toArgb()
                        val tertiaryColorInt = MaterialTheme.colorScheme.tertiary.toArgb()
                        val codeBgColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                        val quoteColor = MaterialTheme.colorScheme.secondary.toArgb()
                        val checkedColor = MaterialTheme.colorScheme.primary.toArgb()
                        val uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                        val colorScheme = MaterialTheme.colorScheme

                        androidx.compose.runtime.key(content) {
                            AndroidView(
                                factory = { context ->
                                    TextView(context).apply {
                                        setTextColor(textColor)
                                        textSize = 16f
                                        setPadding(56, 24, 56, 200) // Keep padding for readability
                                        setLineSpacing(12f, 1.1f)
                                        setTextIsSelectable(true)
                                    }
                                },
                                update = { tv ->
                                    val markwon = Markwon.builder(tv.context)
                                        .usePlugin(StrikethroughPlugin.create())
                                        .usePlugin(TablePlugin.create(tv.context))
                                        .usePlugin(LinkifyPlugin.create())
                                        .usePlugin(TaskListPlugin.create(checkedColor, uncheckedColor, uncheckedColor))
                                        .usePlugin(object : CorePlugin() {
                                            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                                builder.setFactory(org.commonmark.node.Heading::class.java) { _, _ ->
                                                    arrayOf(RelativeSizeSpan(1.5f), StyleSpan(Typeface.BOLD), ForegroundColorSpan(primaryColorInt))
                                                }
                                                builder.setFactory(org.commonmark.node.Code::class.java) { _, _ ->
                                                    arrayOf(BackgroundColorSpan(codeBgColor), ForegroundColorSpan(tertiaryColorInt), TypefaceSpan("monospace"), RelativeSizeSpan(0.90f))
                                                }
                                                builder.setFactory(org.commonmark.node.BlockQuote::class.java) { _, _ ->
                                                    arrayOf(QuoteSpan(quoteColor), StyleSpan(Typeface.ITALIC))
                                                }
                                            }
                                        })
                                        .usePlugin(SyntaxHighlightPlugin.create(
                                            cloud.wafflecommons.pixelbrainreader.ui.utils.PrismHelper.prism,
                                            cloud.wafflecommons.pixelbrainreader.ui.utils.CodeTheme.create(colorScheme)
                                        ))
                                        .build()
                                    markwon.setMarkdown(tv, content)
                                },
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
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

@Composable
fun WelcomeState(onCreateNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Article, 
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

