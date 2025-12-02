package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailPane(
    content: String?,
    isLoading: Boolean,
    isFocusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    isExpandedScreen: Boolean // To conditionally show the button
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    
    // Typography colors for headings
    val headlineColor = MaterialTheme.colorScheme.onSurface.toArgb()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, // V6: Page background
        modifier = if (isExpandedScreen) {
            Modifier.clip(RoundedCornerShape(topStart = 24.dp)) // V6: Clip corner
        } else {
            Modifier
        },
        topBar = {
            if (content != null) {
                TopAppBar(
                    title = { },
                    actions = {
                        if (isExpandedScreen) {
                            IconButton(onClick = onToggleFocusMode) {
                                Icon(
                                    imageVector = if (isFocusMode) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                    contentDescription = if (isFocusMode) "Exit Focus Mode" else "Enter Focus Mode",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent, // V6: Transparent
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (content != null) {
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            setTextColor(textColor)
                            setPadding(32, 32, 32, 32)
                        }
                    },
                    update = { textView ->
                        textView.setTextColor(textColor)
                        
                        val markwon = Markwon.builder(textView.context)
                            .usePlugin(object : CorePlugin() {
                                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                    // Headings
                                    builder.setFactory(org.commonmark.node.Heading::class.java) { _, node ->
                                        val level = (node as org.commonmark.node.Heading).level
                                        val scale = when (level) {
                                            1 -> 2.0f
                                            2 -> 1.5f
                                            else -> 1.25f
                                        }
                                        arrayOf(
                                            android.text.style.RelativeSizeSpan(scale),
                                            android.text.style.StyleSpan(Typeface.BOLD),
                                            android.text.style.ForegroundColorSpan(headlineColor)
                                        )
                                    }
                                    // Code Blocks (Fenced)
                                    builder.setFactory(org.commonmark.node.FencedCodeBlock::class.java) { _, _ ->
                                        arrayOf(
                                            android.text.style.BackgroundColorSpan(codeBackgroundColor),
                                            android.text.style.TypefaceSpan("monospace")
                                        )
                                    }
                                    // Code Blocks (Indented)
                                    builder.setFactory(org.commonmark.node.IndentedCodeBlock::class.java) { _, _ ->
                                        arrayOf(
                                            android.text.style.BackgroundColorSpan(codeBackgroundColor),
                                            android.text.style.TypefaceSpan("monospace")
                                        )
                                    }
                                    // Links
                                    builder.setFactory(org.commonmark.node.Link::class.java) { _, _ ->
                                        android.text.style.ForegroundColorSpan(primaryColor)
                                    }
                                }
                            })
                            .build()
                            
                        markwon.setMarkdown(textView, content)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "Select a file to view content",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
