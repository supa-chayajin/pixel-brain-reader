package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.games.GameMap
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.data.web.GuardedWebViewClient
import cloud.wafflecommons.pixelbrainreader.data.web.configureForImport
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GameMapUiState(
    val loading: Boolean = true,
    val title: String = "",
    val maps: List<GameMap> = emptyList(),
    val selected: Int = 0,
    /** Opened from the session's notification with no game running any more. */
    val noGame: Boolean = false
)

/**
 * A game's map (13.2, route `game_map?page=&index=`): the page's `game_maps`, or, without a page,
 * the running session's game (the Live Update's "Open the map" carries a fixed key, no path).
 */
@HiltViewModel
class GameMapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val games: GamesRepository,
    private val controller: GameSessionController
) : ViewModel() {

    private val pageArg: String = savedStateHandle.get<String>("page").orEmpty()
    private val indexArg: Int = savedStateHandle.get<Int>("index") ?: 0

    private val _state = MutableStateFlow(GameMapUiState())
    val state: StateFlow<GameMapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val path = pageArg.ifEmpty { controller.current()?.page.orEmpty() }
                val page = path.takeIf { it.isNotEmpty() }?.let { games.page(it) }
                _state.value = if (page == null) {
                    GameMapUiState(loading = false, noGame = pageArg.isEmpty())
                } else {
                    GameMapUiState(
                        loading = false,
                        title = page.title,
                        maps = page.maps,
                        selected = indexArg.coerceIn(0, (page.maps.size - 1).coerceAtLeast(0))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = GameMapUiState(loading = false)
            }
        }
    }

    fun select(index: Int) = _state.update { it.copy(selected = index.coerceIn(0, (it.maps.size - 1).coerceAtLeast(0))) }
}

/**
 * The map browser (13.2): the game's map in the app, view-only (no account; what the owner finds
 * goes in the page's checklists), guarded like the share screen's view (public addresses only),
 * counted in the privacy ledger, the screen kept on while it shows (a second screen during play).
 * Back walks the page's history before leaving.
 */
@Composable
fun GameMapScreen(onBack: () -> Unit, onOpenGames: () -> Unit, viewModel: GameMapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val map = state.maps.getOrNull(state.selected)
    if (map == null) {
        FormScaffold(title = state.title.ifEmpty { "Map" }, onBack = onBack) {
            if (!state.loading) {
                EmptyHint(
                    if (state.noGame) "No game is running: open a game's page to see its map."
                    else "No map for this game yet: add one to game_maps in its note."
                )
                if (state.noGame) TextButton(onClick = onOpenGames) { Text("Open Games") }
            }
        }
        return
    }
    MapBrowser(title = state.title, map = map, maps = state.maps, onSelect = viewModel::select, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapBrowser(title: String, map: GameMap, maps: List<GameMap>, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    val holder = remember { MapViewHolder() }
    val context = LocalContext.current
    var progress by remember(map.url) { mutableIntStateOf(0) }
    var failed by remember(map.url) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // The privacy ledger (13.0, X3): the map shown here is one call, from open to close.
    DisposableEffect(map.url) {
        val token = PrivacyLedger.begin(PrivacyLedger.hostOf(map.url), NetPurpose.MAPS)
        onDispose { PrivacyLedger.end(token) }
    }
    BackHandler {
        val view = holder.view
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${map.label} · ${map.site}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (maps.size > 1) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Layers, contentDescription = "Other maps") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                maps.forEachIndexed { index, other ->
                                    DropdownMenuItem(
                                        text = { Text("${other.label} · ${other.site}") },
                                        onClick = {
                                            menu = false
                                            onSelect(index)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = {
                        failed = false
                        holder.view?.reload()
                    }) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = {
                        val address = holder.view?.url ?: map.url
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Icon(Icons.Rounded.OpenInBrowser, contentDescription = "Open in the browser") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            key(map.url) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureForImport(loadImages = true)
                            keepScreenOn = true
                            webViewClient = object : GuardedWebViewClient() {
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) failed = true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(map.url)
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
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
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
}

/** The map's WebView, outside snapshot state: only the callbacks read it. */
private class MapViewHolder {
    var view: WebView? = null
}
