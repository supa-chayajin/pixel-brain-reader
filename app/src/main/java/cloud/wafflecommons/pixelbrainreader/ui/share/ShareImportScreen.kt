package cloud.wafflecommons.pixelbrainreader.ui.share

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.web.ClipQuality
import cloud.wafflecommons.pixelbrainreader.data.web.ClipSummarizer
import cloud.wafflecommons.pixelbrainreader.data.web.GuardedWebViewClient
import cloud.wafflecommons.pixelbrainreader.data.web.LoadStep
import cloud.wafflecommons.pixelbrainreader.data.web.PageProblem
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImport
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImportRepository
import cloud.wafflecommons.pixelbrainreader.data.web.configureForImport
import cloud.wafflecommons.pixelbrainreader.data.web.defaultTitle
import cloud.wafflecommons.pixelbrainreader.data.web.readDom
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FolderPickerDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.main.MarkwonContent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ShareImportViewModel @Inject constructor(
    private val repository: ShareImportRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    localAiManager: LocalAiManager
) : ViewModel() {

    val state: StateFlow<ShareImport?> = repository.state
    val generation: StateFlow<Int> = repository.generation

    /** The summary option shows only when Gemini Nano can answer. */
    val canSummarize: StateFlow<Boolean> = localAiManager.nanoState
        .map { it is NanoState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _saved = Channel<String>(Channel.BUFFERED)

    /** The path of each note saved. */
    val saved: Flow<String> = _saved.receiveAsFlow()

    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = vaultDiscoveryRepository.getAllFolderPaths()
                .filter { path -> !path.startsWith(".") && !path.startsWith("99_") && !path.startsWith("10_Journal") && path.count { it == '/' } <= 2 }
                .sorted()
        }
    }

    fun retry() = repository.retry()

    fun importRendered(html: String, url: String) = repository.importRendered(html, url)

    fun dismiss() = repository.dismiss()

    fun save(title: String, folder: String, editedMarkdown: String?, summarize: Boolean) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                _saved.send(repository.save(title, folder, editedMarkdown, summarize))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Couldn't save the note: ${e.message}")
            } finally {
                _saving.value = false
            }
        }
    }
}

/**
 * The share screen: what another app shared, read as an article (title, source, a preview to
 * check or edit), as a link the page wouldn't give up, or as text, saved as a vault note.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareImportScreen(viewModel: ShareImportViewModel, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val canSummarize by viewModel.canSummarize.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(Unit) {
        viewModel.loadFolders()
        launch { viewModel.saved.collect { currentOnSaved(it) } }
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    val close = {
        viewModel.dismiss()
        onClose()
    }
    BackHandler(onBack = close)

    // Saving clears the import: the screen keeps showing it while it leaves.
    val current = rememberLastNonNull(state)
    val today = remember { LocalDate.now() }
    var title by remember(current) { mutableStateOf(current?.defaultTitle(today).orEmpty()) }
    var folder by rememberSaveable { mutableStateOf(VaultSchema.INBOX_FOLDER) }
    var markdown by remember(current) { mutableStateOf(current?.body().orEmpty()) }
    var edited by remember(current) { mutableStateOf(false) }
    var editing by remember(current) { mutableStateOf(current is ShareImport.Text) }
    var summarize by remember(current) { mutableStateOf(true) }
    var pickingFolder by remember { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val summaryOffered = canSummarize && current is ShareImport.Article && markdown.length >= ClipSummarizer.MIN_CHARS

    browsing?.let { url ->
        PageBrowser(
            url = url,
            onImport = { html, at ->
                browsing = null
                viewModel.importRendered(html, at)
            },
            onClose = { browsing = null }
        )
        return
    }

    FormScaffold(
        title = "Save to vault",
        onBack = close,
        snackbarHostState = snackbarHostState,
        actions = {
            if (current != null && current !is ShareImport.Loading) {
                Button(
                    enabled = !saving && title.isNotBlank(),
                    onClick = { viewModel.save(title, folder, markdown.takeIf { edited }, summarize && summaryOffered) },
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(if (current is ShareImport.LinkOnly) "Save link" else "Save") }
            }
        }
    ) {
        if (saving) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                if (summarize && summaryOffered) "Summarizing on the device, then saving…" else "Saving…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (current) {
            null -> EmptyHint("Nothing to import.")
            is ShareImport.Loading -> LoadingCard(current, onCancel = close)
            is ShareImport.Article -> {
                NoteFields(title, { title = it }, folder, { folder = it }, onPickFolder = { pickingFolder = true })
                ArticleCard(current, onOpenHere = { browsing = current.clip.url })
                current.passage?.let { PassageCard(it) }
                if (summaryOffered) SummaryRow(summarize) { summarize = it }
                PreviewOrEdit(markdown, editing, onEditingChange = { editing = it }) {
                    markdown = it
                    edited = true
                }
            }
            is ShareImport.LinkOnly -> {
                LinkOnlyCard(current, onRetry = viewModel::retry, onOpenHere = { browsing = current.url })
                NoteFields(title, { title = it }, folder, { folder = it }, onPickFolder = { pickingFolder = true })
                current.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current.passage?.let { PassageCard(it) }
            }
            is ShareImport.Text -> {
                NoteFields(title, { title = it }, folder, { folder = it }, onPickFolder = { pickingFolder = true })
                PreviewOrEdit(markdown, editing, onEditingChange = { editing = it }) {
                    markdown = it
                    edited = true
                }
            }
        }
    }
    if (pickingFolder) {
        FolderPickerDialog(folders = folders, onPick = { folder = it; pickingFolder = false }, onDismiss = { pickingFolder = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingCard(loading: ShareImport.Loading, onCancel: () -> Unit) {
    SectionCard(
        title = loading.url?.let(::host) ?: "Shared text",
        icon = Icons.Rounded.Language,
        subtitle = when (loading.step) {
            LoadStep.DOWNLOAD -> "Downloading the page…"
            LoadStep.BROWSER -> "Opening the page the way a browser would…"
            LoadStep.READ -> "Reading the page…"
        }
    ) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (loading.step == LoadStep.BROWSER) {
            Text(
                "The site didn't hand the article over directly. This can take up to half a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun NoteFields(
    title: String,
    onTitleChange: (String) -> Unit,
    folder: String,
    onFolderChange: (String) -> Unit,
    onPickFolder: () -> Unit
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = folder,
        onValueChange = onFolderChange,
        label = { Text("Folder") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onPickFolder) { Icon(Icons.Rounded.FolderOpen, contentDescription = "Choose a folder") }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ArticleCard(article: ShareImport.Article, onOpenHere: () -> Unit) {
    val clip = article.clip
    val extent = if (clip.isVideo) "Video" else "${clip.words} words · ${readMinutes(clip.words)} min read"
    SectionCard(
        title = clip.siteName ?: host(clip.url),
        icon = if (clip.isVideo) Icons.Rounded.SmartDisplay else Icons.AutoMirrored.Rounded.Article,
        subtitle = listOfNotNull(clip.author, clip.published, extent).joinToString(" · ")
    ) {
        if (clip.quality == ClipQuality.PARTIAL) {
            Text(
                "Only part of the page could be read. The note says so and links to the full article. " +
                    "If a banner or a check hides the rest, open the page here and import it once it shows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            TextButton(onClick = onOpenHere) { Text("Open it here") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkOnlyCard(link: ShareImport.LinkOnly, onRetry: () -> Unit, onOpenHere: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SectionCard(
        title = "This page couldn't be read",
        icon = Icons.Rounded.LinkOff,
        tint = MaterialTheme.colorScheme.error,
        subtitle = host(link.url)
    ) {
        Text(problemText(link.problem, link.status, link.detail), style = MaterialTheme.typography.bodyMedium)
        val openable = link.problem.worthABrowser || link.problem == PageProblem.NO_TEXT
        Text(
            if (openable) {
                "Open the page here, get past what stops it (a check, a banner), then import it as you see it. " +
                    "Or save the link: the note keeps the address, the reason and what you shared with it."
            } else {
                "You can still save the link: the note keeps the address, the reason and what you shared with it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (openable) Button(onClick = onOpenHere) { Text("Open it here") }
            if (link.problem.worthABrowser) OutlinedButton(onClick = onRetry) { Text("Try again") }
            TextButton(onClick = { runCatching { uriHandler.openUri(link.url) } }) { Text("Open in browser") }
        }
    }
}

@Composable
private fun PassageCard(passage: String) {
    SectionCard(title = "Your highlight", icon = Icons.Rounded.FormatQuote, subtitle = "Kept in the note as a quote") {
        Text(passage, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SummaryRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Add a summary", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Three bullet points by Gemini Nano, on the device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewOrEdit(markdown: String, editing: Boolean, onEditingChange: (Boolean) -> Unit, onMarkdownChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !editing,
            onClick = { onEditingChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null) }
        ) { Text("Preview") }
        SegmentedButton(
            selected = editing,
            onClick = { onEditingChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null) }
        ) { Text("Markdown") }
    }
    if (editing) {
        OutlinedTextField(
            value = markdown,
            onValueChange = onMarkdownChange,
            minLines = 12,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(16.dp)) {
                if (markdown.isBlank()) EmptyHint("No text.") else MarkwonContent(content = markdown, onWikiLinkClick = {})
            }
        }
    }
}

/**
 * The page in a browser inside the share screen, for what the off-screen read can't get through:
 * a human check to pass, a consent wall to close. "Import this page" clips the page as it shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBrowser(url: String, onImport: (html: String, url: String) -> Unit, onClose: () -> Unit) {
    val holder = remember { WebViewHolder() }
    var progress by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler {
        val view = holder.view
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(host(url), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Import the page once it shows", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    CortexIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close the page") }
                },
                actions = {
                    Button(
                        enabled = !reading,
                        onClick = {
                            val view = holder.view ?: return@Button
                            reading = true
                            scope.launch {
                                val html = view.readDom()
                                reading = false
                                // A check passed here holds for the next imports from the site.
                                CookieManager.getInstance().flush()
                                if (html != null) onImport(html, view.url ?: url)
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("Import this page") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        configureForImport(loadImages = true)
                        webViewClient = GuardedWebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(url)
                        holder.view = this
                    }
                },
                onRelease = { view ->
                    holder.view = null
                    view.stopLoading()
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The browser's WebView, outside snapshot state: only the callbacks read it. */
private class WebViewHolder {
    var view: WebView? = null
}

/** The last non-null [value]. A plain holder, written during composition on purpose: it never triggers one. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { LastValue<T>() }
    if (value != null) last.value = value
    return value ?: last.value
}

private class LastValue<T : Any> {
    var value: T? = null
}

private fun ShareImport.body(): String = when (this) {
    is ShareImport.Article -> clip.markdown
    is ShareImport.Text -> markdown
    else -> ""
}

private fun problemText(problem: PageProblem, status: Int?, detail: String?): String {
    val http = status?.let { " (HTTP $it)" }.orEmpty()
    return when (problem) {
        PageProblem.NOT_PUBLIC -> "This address isn't a public web page, so it isn't fetched."
        PageProblem.NOT_HTML -> "This link isn't a web page${detail?.let { " ($it)" }.orEmpty()}."
        PageProblem.GONE -> "The page doesn't exist anymore$http."
        PageProblem.PAYWALL -> "The page is for subscribers only$http."
        PageProblem.REFUSED -> "The site refused to serve the page$http."
        PageProblem.BLOCKED -> "The site asks for a human check, even in the browser view."
        PageProblem.SERVER -> "The site isn't answering properly$http."
        PageProblem.NETWORK -> "The page couldn't be loaded. Check the connection and try again."
        PageProblem.NO_TEXT -> "The page has no readable text: it's built by scripts or kept for subscribers."
    }
}

private fun readMinutes(words: Int): Int = maxOf(1, (words / WORDS_PER_MINUTE).roundToInt())

private fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

private const val WORDS_PER_MINUTE = 230.0
