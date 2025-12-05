package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
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
    isFocusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    isExpandedScreen: Boolean
) {
    val shape = if (isExpandedScreen && !isFocusMode) {
        RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    val padding = if (isExpandedScreen && !isFocusMode) {
        PaddingValues(start = 12.dp)
    } else {
        PaddingValues(all = 0.dp)
    }

    // Couleurs du thème
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        modifier = Modifier
            .padding(padding)
            .clip(shape),
        containerColor = surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    if (content != null) {
                        Column {
                            Text(
                                text = fileName ?: "Document",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onSurface
                            )
                            Text(
                                text = "Markdown • Lecture seule",
                                style = MaterialTheme.typography.labelSmall,
                                color = primary
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
                                tint = onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (content != null) {
                val textColor = onSurface.toArgb()
                val primaryColorInt = primary.toArgb()
                val tertiaryColorInt = tertiary.toArgb()
                val codeBgColor = surfaceVariant.toArgb()
                val quoteColor = secondary.toArgb()
                val checkedColor = primary.toArgb()
                val uncheckedColor = onSurfaceVariant.toArgb()

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
                            // Configuration des cases à cocher (Couleur active / inactive)
                            .usePlugin(TaskListPlugin.create(checkedColor, uncheckedColor, uncheckedColor))
                            .usePlugin(object : CorePlugin() {
                                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                    // Titres H1-H6
                                    builder.setFactory(org.commonmark.node.Heading::class.java) { _, _ ->
                                        arrayOf(
                                            RelativeSizeSpan(1.5f),
                                            StyleSpan(Typeface.BOLD),
                                            ForegroundColorSpan(primaryColorInt)
                                        )
                                    }

                                    // Code Blocks (Sans Prism, mais stylisé proprement)
                                    builder.setFactory(org.commonmark.node.FencedCodeBlock::class.java) { _, _ ->
                                        arrayOf(
                                            BackgroundColorSpan(codeBgColor),
                                            ForegroundColorSpan(tertiaryColorInt),
                                            TypefaceSpan("monospace"),
                                            RelativeSizeSpan(0.90f)
                                        )
                                    }

                                    // Code Inline
                                    builder.setFactory(org.commonmark.node.Code::class.java) { _, _ ->
                                        arrayOf(
                                            BackgroundColorSpan(codeBgColor),
                                            ForegroundColorSpan(tertiaryColorInt),
                                            TypefaceSpan("monospace"),
                                            RelativeSizeSpan(0.90f)
                                        )
                                    }

                                    // Citations
                                    builder.setFactory(org.commonmark.node.BlockQuote::class.java) { _, _ ->
                                        arrayOf(
                                            QuoteSpan(quoteColor),
                                            StyleSpan(Typeface.ITALIC)
                                        )
                                    }
                                }
                            })
                            .build()

                        markwon.setMarkdown(tv, content)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "Sélectionnez un fichier",
                    modifier = Modifier.align(Alignment.Center),
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}