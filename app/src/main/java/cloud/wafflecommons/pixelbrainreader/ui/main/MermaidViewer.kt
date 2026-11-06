package cloud.wafflecommons.pixelbrainreader.ui.main

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberCopyToClipboard
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/**
 * The page that draws one Mermaid diagram (Step 7, owner decision D3): the bundled
 * `assets/mermaid/mermaid.min.js` (Mermaid 12.2.0, MIT, from the npm tarball whose integrity was
 * checked; SHA-256 a8a6a6f8…c3b9f), the diagram's source as a script string, and nothing else.
 * Mermaid runs with `securityLevel: 'strict'` (labels are sanitized, no click handlers).
 */
internal object MermaidPage {
    const val BASE_URL = "file:///android_asset/mermaid/"
    const val SCRIPT_URL = BASE_URL + "mermaid.min.js"

    /**
     * What the viewer may load: the page itself (WebView requests it as a `data:` URL) and the
     * bundled script. Everything else, any scheme, is refused.
     */
    fun allows(url: String): Boolean = url == SCRIPT_URL || url.startsWith("data:")

    /** [source] as a JS string literal that can't close the script element nor break the line. */
    fun jsString(source: String): String = Json.encodeToString(String.serializer(), source)
        .replace("<", "\\u003c")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")

    /** "flowchart", "sequenceDiagram", "gantt"…: the first word that isn't a comment or directive. */
    fun kindOf(source: String): String = source.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("%%") && !it.startsWith("---") }
        ?.substringBefore(' ')
        ?.ifEmpty { null } ?: "diagram"

    fun html(source: String, dark: Boolean, background: String, foreground: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=8, user-scalable=yes">
        <style>html,body{margin:0;background:$background;color:$foreground;font-family:sans-serif}#d{padding:16px}#e{padding:16px;white-space:pre-wrap}</style>
        <script src="mermaid.min.js"></script>
        </head><body><div id="d"></div><div id="e">Drawing…</div>
        <script>
        (function () {
          var source = ${jsString(source)};
          var note = document.getElementById('e');
          function fail(e) { note.textContent = 'Could not draw this diagram: ' + (e && e.message ? e.message : e); }
          try {
            mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: '${if (dark) "dark" else "default"}' });
            mermaid.render('diagram', source).then(function (r) { document.getElementById('d').innerHTML = r.svg; note.textContent = ''; }).catch(fail);
          } catch (e) { fail(e); }
        })();
        </script>
        </body></html>
    """.trimIndent()
}

/** Nothing but the bundled script: every other request gets a 403 and no page ever navigates. */
private object OfflineMermaidClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (MermaidPage.allows(request.url.toString())) null
        else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

private fun Color.hex(): String = "#" + Integer.toHexString(toArgb() and 0xFFFFFF).padStart(6, '0')

/**
 * The diagram drawn offline. Scripts must run (Mermaid draws with them), but only the bundled file
 * is ever loaded: network loads are blocked, the client refuses every other request, and files and
 * content providers stay out of reach.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidWebView(source: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val html = remember(source, dark, colors.surface, colors.onSurface) {
        MermaidPage.html(source, dark, colors.surface.hex(), colors.onSurface.hex())
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                with(settings) {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webViewClient = OfflineMermaidClient
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(MermaidPage.BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier
    )
}

/** A ```mermaid block in the reader: what kind of diagram, its first lines, and a way to see it drawn. */
@Composable
fun MermaidCard(source: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(source) { mutableStateOf(false) }
    val copy = rememberCopyToClipboard()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClickLabel = "View the diagram") { open = true }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Diagram · ${MermaidPage.kindOf(source)}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                source.lines().take(PREVIEW_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                TextButton(onClick = { open = true }) { Text("View diagram") }
                TextButton(onClick = { copy(source) }) { Text("Copy source") }
            }
        }
    }
    if (open) MermaidDialog(source, onDismiss = { open = false }, onCopy = { copy(source) })
}

@Composable
private fun MermaidDialog(source: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Text("Diagram · ${MermaidPage.kindOf(source)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCopy) { Text("Copy source") }
                }
                Text(
                    "Drawn on the phone, offline. Pinch to zoom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                MermaidWebView(source, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private const val PREVIEW_LINES = 4
