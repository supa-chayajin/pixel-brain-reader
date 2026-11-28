@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameMap
import cloud.wafflecommons.pixelbrainreader.data.games.GameMaps
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.MapEditResult
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureSource
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.web.GuardedWebViewClient
import cloud.wafflecommons.pixelbrainreader.data.web.blockedResponse
import cloud.wafflecommons.pixelbrainreader.data.web.configureForImport
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.rounded.Map

data class GameMapUiState(
    val loading: Boolean = true,
    val title: String = "",
    val maps: List<GameMap> = emptyList(),
    val selected: Int = 0,
    /** Opened from the session's notification with no game running any more. */
    val noGame: Boolean = false,
    /** The game's page (13.3): where a map edit goes; empty without a game. */
    val path: String = "",
    /**
     * A read-only device changes no map (13.3, ME3): the browser's controls only hide, a full-screen
     * browser having no room for the notice.
     */
    val readOnly: Boolean = VaultWriteGate.readOnly,
    /** The game's picture for the browser's header (13.4, P1); null until read, or without a game. */
    val art: GameArt? = null
)

/**
 * A game's map (13.2, route `game_map?page=&index=`): the page's `game_maps`, or, without a page,
 * the running session's game (the Live Update's "Open the map" carries a fixed key, no path).
 * 13.3 (ME3): the map shown can be changed from here, given the page the browser is at, or kept
 * beside as another map; each edit reads the page from disk and names the map by its address.
 * 13.4: the map picked from the Maps menu is kept in the saved state ([SELECTED]), so a recreation,
 * or the process coming back, reopens it rather than the route's own index.
 */
@HiltViewModel
class GameMapViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val games: GamesRepository,
    private val controller: GameSessionController,
    private val pictures: GamePictureSource
) : ViewModel() {

    private val pageArg: String = savedStateHandle.get<String>("page").orEmpty()
    private val indexArg: Int = savedStateHandle.get<Int>("index") ?: 0

    private val _state = MutableStateFlow(GameMapUiState())
    val state: StateFlow<GameMapUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** What each map edit did (13.3), for the browser's snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val path = pageArg.ifEmpty { controller.current()?.page.orEmpty() }
                val page = path.takeIf { it.isNotEmpty() }?.let { games.page(it) }
                if (page == null) {
                    _state.value = GameMapUiState(loading = false, noGame = pageArg.isEmpty())
                    return@launch
                }
                // The map picked before a recreation first, then the route's own.
                val selected = (savedStateHandle.get<Int>(SELECTED) ?: indexArg).coerceIn(0, (page.maps.size - 1).coerceAtLeast(0))
                savedStateHandle[SELECTED] = selected
                _state.value = GameMapUiState(
                    loading = false,
                    title = page.title,
                    maps = page.maps,
                    selected = selected,
                    path = path
                )
                // The picture is decoration: read after the map shows, never holding it back.
                val art = pictures.artFor(page)
                _state.update { it.copy(art = art) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = GameMapUiState(loading = false)
            }
        }
    }

    fun select(index: Int) {
        _state.update { it.copy(selected = index.coerceIn(0, (it.maps.size - 1).coerceAtLeast(0))) }
        savedStateHandle[SELECTED] = _state.value.selected
    }

    /** The map shown given the address [url] and the label [label], in its place. */
    fun change(url: String, label: String?) {
        val selected = selectedMap() ?: return
        edit(saved = "Map saved", select = url) { path -> games.changeMap(path, selected.url, url, label) }
    }

    /**
     * The map shown takes the address the browser is at now ([currentUrl], after moving inside the
     * site); its typed label stays.
     */
    fun useCurrentPage(currentUrl: String?) {
        val selected = selectedMap() ?: return
        val address = currentUrl?.trim().orEmpty()
        if (!GameMaps.accepts(address)) {
            viewModelScope.launch { _messages.send("This page's address can't be a map: only public https addresses.") }
            return
        }
        edit(saved = "Map saved", select = address) { path -> games.changeMap(path, selected.url, address, selected.given) }
    }

    /** [url] added to the game's maps, then shown. */
    fun addMap(url: String, label: String?) {
        edit(saved = "Map added", select = url) { path -> games.addMap(path, url, label) }
    }

    private fun selectedMap(): GameMap? = _state.value.let { it.maps.getOrNull(it.selected) }

    /** Runs one edit on the game's page and says how it went; once saved, the page is read again and [select] shown. */
    private fun edit(saved: String, select: String, call: suspend (path: String) -> MapEditResult) {
        val path = _state.value.path
        if (path.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                call(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapEditResult.FAILED
            }
            if (result == MapEditResult.SAVED) reload(path, select.trim())
            _messages.send(result.message(saved))
        }
    }

    /** The page's maps read again, the map at [url] selected (its place may have moved). */
    private suspend fun reload(path: String, url: String) {
        val page = try {
            games.page(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (page == null) return
        val index = page.maps.indexOfFirst { it.url == url }
        _state.update {
            it.copy(
                title = page.title,
                maps = page.maps,
                selected = if (index >= 0) index else it.selected.coerceIn(0, (page.maps.size - 1).coerceAtLeast(0))
            )
        }
        savedStateHandle[SELECTED] = _state.value.selected
    }

    private companion object {
        /** The saved state's key of the map shown (13.4): in memory and saved state only, never persisted elsewhere. */
        const val SELECTED = "map_selected"
    }
}

/**
 * The map browser (13.2): the game's map in the app, view-only (no account; what the owner finds
 * goes in the page's checklists), guarded like the share screen's view (public addresses only),
 * counted in the privacy ledger, the screen kept on while it shows (a second screen during play).
 * Back walks the page's history before leaving. 13.3: one Maps menu, the other maps then the edits
 * (this map's address or label, the page shown now, or another map); a read-only device keeps the
 * other maps alone.
 */
@Composable
fun GameMapScreen(onBack: () -> Unit, onOpenGames: () -> Unit, viewModel: GameMapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var adding by rememberSaveable { mutableStateOf(false) }
    val map = state.maps.getOrNull(state.selected)
    if (map == null) {
        val canAdd = !state.readOnly && state.path.isNotEmpty()
        FormScaffold(title = state.title.ifEmpty { "Map" }, icon = Icons.Rounded.Map, onBack = onBack, snackbarHostState = snackbar) {
            if (!state.loading) {
                EmptyHint(
                    when {
                        state.noGame -> "No game is running: open a game's page to see its map."
                        canAdd -> "No map for this game yet: add one here or on the game's page, or share a map page to the app from your browser."
                        else -> "No map for this game yet."
                    }
                )
                if (state.noGame) TextButton(onClick = onOpenGames, shapes = ButtonDefaults.shapes()) { Text("Open Games") }
                if (canAdd) TextButton(onClick = { adding = true }, shapes = ButtonDefaults.shapes()) { Text("Add a map") }
            }
        }
        if (adding && canAdd) {
            MapEditDialog(
                title = "Add a map",
                initialUrl = "",
                initialLabel = null,
                confirmLabel = "Add",
                onSave = { url, label ->
                    adding = false
                    viewModel.addMap(url, label)
                },
                onDismiss = { adding = false }
            )
        }
        return
    }
    MapBrowser(
        title = state.title,
        art = state.art,
        map = map,
        maps = state.maps,
        readOnly = state.readOnly,
        snackbar = snackbar,
        onSelect = viewModel::select,
        onChange = viewModel::change,
        onUseCurrentPage = viewModel::useCurrentPage,
        onAddMap = viewModel::addMap,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapBrowser(
    title: String,
    art: GameArt?,
    map: GameMap,
    maps: List<GameMap>,
    readOnly: Boolean,
    snackbar: SnackbarHostState,
    onSelect: (Int) -> Unit,
    onChange: (url: String, label: String?) -> Unit,
    onUseCurrentPage: (currentUrl: String?) -> Unit,
    onAddMap: (url: String, label: String?) -> Unit,
    onBack: () -> Unit
) {
    val holder = remember { MapViewHolder() }
    val context = LocalContext.current
    // 13.3 (ME3): a new view only for a real switch of map. A map given the address the view is at
    // ("Use this page", "Add as another map…" of the page shown) keeps it, with its pan, zoom and
    // Back history. A count, not the address, keys the view: a map changed back to the address the
    // view was built for still loads it. 13.4: both saved, so a recreation (the system's dark theme
    // switching at dusk) keeps the view's place, with the address it had reached ([lastUrl]).
    var viewFor by rememberSaveable { mutableStateOf(map.url) }
    var viewGeneration by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(map.url) {
        if (map.url != viewFor) {
            if (holder.view?.url != map.url) viewGeneration++
            viewFor = map.url
        }
    }
    // The address the view went to last inside the map's site; a new view starts from its map.
    var lastUrl by rememberSaveable(viewGeneration) { mutableStateOf<String?>(null) }
    var progress by remember(viewGeneration) { mutableIntStateOf(0) }
    var failed by remember(viewGeneration) { mutableStateOf(false) }
    // 13.3 (ME3): the top bar's one menu (Maps; on a read-only device, Other maps alone), the
    // browser's address and title read as it opens.
    var menu by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var changing by rememberSaveable { mutableStateOf(false) }
    var addUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var addLabel by rememberSaveable { mutableStateOf<String?>(null) }
    // The privacy ledger (13.0, X3): the map shown here is one call while it is on screen. 13.4: the
    // span ends and the page pauses when the app leaves the screen (a locked phone, another app), and
    // both come back with it; a hidden page's traffic is then "Not named", never billed to the map.
    // No pauseTimers(): it would stall every WebView of the process, an import's render included.
    LifecycleStartEffect(map.url) {
        val token = PrivacyLedger.begin(PrivacyLedger.hostOf(map.url), NetPurpose.MAPS)
        holder.view?.onResume()
        onStopOrDispose {
            PrivacyLedger.end(token)
            holder.view?.onPause()
        }
    }
    BackHandler {
        val view = holder.view
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    // 13.4 (P1): the game's picture in the header's emblem, the map's emblem until it is read.
    val headerPicture: (@Composable (Dp) -> Unit)? =
        art?.let { game -> @Composable { size: Dp -> GamePicture(game, Modifier.size(size), contentDescription = null) } }
    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = title,
                subtitle = "${map.label} · ${map.site}",
                icon = androidx.compose.material.icons.Icons.Rounded.Map,
                picture = headerPicture,
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (readOnly) {
                        // No map edits here: the other maps alone, as in 13.2.
                        if (maps.size > 1) {
                            Box {
                                IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Layers, contentDescription = "Other maps") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    MapChoices(maps) { index ->
                                        menu = false
                                        onSelect(index)
                                    }
                                }
                            }
                        }
                    } else {
                        // One menu, so a phone's bar keeps room for the game's name: the other maps,
                        // then this map's edits.
                        Box {
                            IconButton(onClick = {
                                pageUrl = holder.view?.url
                                pageTitle = holder.view?.title
                                menu = true
                            }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.EditLocationAlt, contentDescription = "Maps") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (maps.size > 1) {
                                    MapChoices(maps) { index ->
                                        menu = false
                                        onSelect(index)
                                    }
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Change this map…") },
                                    onClick = {
                                        menu = false
                                        changing = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Use this page") },
                                    enabled = pageUrl != null && pageUrl != map.url,
                                    onClick = {
                                        menu = false
                                        onUseCurrentPage(pageUrl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add as another map…") },
                                    onClick = {
                                        menu = false
                                        addUrl = pageUrl ?: map.url
                                        addLabel = GameMaps.cleanLabel(pageTitle)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        failed = false
                        holder.view?.reload()
                    }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = {
                        val address = holder.view?.url ?: map.url
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.OpenInBrowser, contentDescription = "Open in the browser") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            key(viewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureForImport(loadImages = true)
                            keepScreenOn = true
                            webViewClient = object : GuardedWebViewClient() {
                                // 13.4: a map's main frame stays on https (the share screen's view still shows http pages).
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (request.isForMainFrame && !request.url.scheme.equals(HTTPS, ignoreCase = true)) return true
                                    return super.shouldOverrideUrlLoading(view, request)
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (request.isForMainFrame && request.url.scheme.equals(HTTP, ignoreCase = true)) return blockedResponse()
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (url != null) lastUrl = url
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) failed = true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            // A view rebuilt after a recreation goes back where it was, when that address can be a map.
                            loadUrl(lastUrl?.takeIf(GameMaps::accepts) ?: map.url)
                            holder.view = this
                        }
                    },
                    onRelease = { view ->
                        // A new map's view may already be in the holder: only this one leaves it.
                        if (holder.view === view) holder.view = null
                        view.stopLoading()
                        view.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (progress < 100) LinearWavyProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (failed) {
                ElevatedCard(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text(
                        "The map didn't load. Check the connection and reload, or open it in the browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (changing && !readOnly) {
        MapEditDialog(
            title = "Change this map",
            initialUrl = map.url,
            initialLabel = map.given,
            onSave = { url, label ->
                changing = false
                onChange(url, label)
            },
            onDismiss = { changing = false }
        )
    }
    addUrl?.takeIf { !readOnly }?.let { url ->
        MapEditDialog(
            title = "Add another map",
            initialUrl = url,
            initialLabel = addLabel,
            confirmLabel = "Add",
            onSave = { address, label ->
                addUrl = null
                addLabel = null
                onAddMap(address, label)
            },
            onDismiss = {
                addUrl = null
                addLabel = null
            }
        )
    }
}

/** The game's maps as menu items, "<label> · <site>" each; a pick gives the map's place. */
@Composable
private fun MapChoices(maps: List<GameMap>, onPick: (Int) -> Unit) {
    maps.forEachIndexed { index, other ->
        DropdownMenuItem(text = { Text("${other.label} · ${other.site}") }, onClick = { onPick(index) })
    }
}

/** The map's WebView, outside snapshot state: only callbacks and effects read it. */
private class MapViewHolder {
    var view: WebView? = null
}

private const val HTTPS = "https"
private const val HTTP = "http"
