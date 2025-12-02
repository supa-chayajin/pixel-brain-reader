package cloud.wafflecommons.pixelbrainreader.ui.main

import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun FileDetailPane(
    content: String?,
    isLoading: Boolean
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (content != null) {
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            setTextColor(textColor)
                            // Add padding to TextView itself if needed, or rely on Compose padding
                            setPadding(32, 32, 32, 32) 
                        }
                    },
                    update = { textView ->
                        textView.setTextColor(textColor) // Ensure color updates on theme change
                        Markwon.create(textView.context).setMarkdown(textView, content)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "Select a file to view content",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
