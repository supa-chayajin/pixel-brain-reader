package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailPane(
    content: String?,
    fileName: String? = null,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isFocusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    isExpandedScreen: Boolean
) {
    // --- LOGIC WINDOWS ---
    val shape = if (isExpandedScreen) {
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    val padding = if (isExpandedScreen) {
        if (isFocusMode) {
            PaddingValues(16.dp)
        } else {
            PaddingValues(start = 8.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
        }
    } else {
        PaddingValues(0.dp)
    }

    // Inset Logic:
    // If NOT expanded (Mobile), we want the Scaffold content to be pushed down by status bars.
    // If expanded (Tablet), the padding above handles some of it, but we need to ensure we don't clip.
    // The card itself should be pushed down.
    
    val modifier = Modifier
        .padding(padding)
        // Apply status bar padding ONLY if we are in mobile mode acting as a full screen window, 
        // OR if needed in tablet mode to push the floating card down.
        // Actually, if we apply it here, it pushes the whole card down.
        .statusBarsPadding() 
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
             Column {
                TopAppBar(
                    title = {
                        if (content != null) {
                            Column {
                                Text(
                                    text = fileName ?: "Document",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Markdown • Lecture seule",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        if (isExpandedScreen) {
                            IconButton(onClick = onToggleFocusMode) {
                                Icon(
                                    imageVector = if (isFocusMode) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                    contentDescription = "Mode Focus",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    ) { innerPadding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(innerPadding).fillMaxSize()
        ) {
            if (isLoading && content == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (content != null) {
                // Colors
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val primaryColorInt = MaterialTheme.colorScheme.primary.toArgb()
                val tertiaryColorInt = MaterialTheme.colorScheme.tertiary.toArgb()
                val codeBgColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                val quoteColor = MaterialTheme.colorScheme.secondary.toArgb()
                val checkedColor = MaterialTheme.colorScheme.primary.toArgb()
                val uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            setTextColor(textColor)
                            textSize = 16f
                            setPadding(56, 24, 56, 200)
                            setLineSpacing(12f, 1.1f)
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
                                    builder.setFactory(org.commonmark.node.FencedCodeBlock::class.java) { _, _ ->
                                        arrayOf(BackgroundColorSpan(codeBgColor), ForegroundColorSpan(tertiaryColorInt), TypefaceSpan("monospace"), RelativeSizeSpan(0.90f))
                                    }
                                    builder.setFactory(org.commonmark.node.Code::class.java) { _, _ ->
                                        arrayOf(BackgroundColorSpan(codeBgColor), ForegroundColorSpan(tertiaryColorInt), TypefaceSpan("monospace"), RelativeSizeSpan(0.90f))
                                    }
                                    builder.setFactory(org.commonmark.node.BlockQuote::class.java) { _, _ ->
                                        arrayOf(QuoteSpan(quoteColor), StyleSpan(Typeface.ITALIC))
                                    }
                                }
                            })
                            .build()
                        markwon.setMarkdown(tv, content)
                    },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
            } else {
                 Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sélectionnez un fichier", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                 }
            }
        }
    }
}
