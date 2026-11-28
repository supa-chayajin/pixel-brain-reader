@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GamePictures
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionGuard
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.OrphanedPage
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.components.toneFor
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.ContentMaxWidth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Games tab (RFC-009 §4.1; 13.4, P2: a grid of covers, the review's ui #7). Its own Scaffold
 * under the tab's header ("Games": the tab's drawn emblem matches it), over [GamesContent]. The tab
 * reads the vault only while it is on screen ([GamesViewModel.watch]); a hide, a link and the other
 * fold actions answer in the snackbar, with an Undo where one applies.
 */
@Composable
fun GamesScreen(
    onOpenGame: (String) -> Unit,
    onOpenPsn: () -> Unit,
    onOpenXbox: () -> Unit = {},
    onOpenMap: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: GamesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<UnlinkedTitle?>(null) }
    var relinking by remember { mutableStateOf<OrphanedPage?>(null) }

    // 13.4: the tab reads only while it is started (the audit's background reloads); each return loads once.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    LaunchedEffect(viewModel, snackbar) {
        viewModel.messages.collect { message ->
            // A newer line takes the place of the one showing (hides in a row), never waits behind it.
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val undo = message.undo
                val result = snackbar.showSnackbar(
                    message = message.text,
                    actionLabel = if (undo != null) "Undo" else null,
                    duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed && undo != null) viewModel.undo(undo)
            }
        }
    }

    val actions = remember(viewModel, onOpenGame, onOpenPsn, onOpenXbox, onOpenMap) {
        GamesActions(
            onOpenGame = onOpenGame,
            onOpenMap = onOpenMap,
            onOpenPsn = onOpenPsn,
            onOpenXbox = onOpenXbox,
            onStart = { picking = true },
            onStop = viewModel::stop,
            onKeepPlaying = viewModel::keepPlaying,
            onLink = { linking = it },
            onLinkTo = viewModel::link,
            onCreatePage = viewModel::createPage,
            onHide = viewModel::hide,
            onUnhide = viewModel::unhide,
            onLinkOrphan = { relinking = it }
        )
    }

    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = "Games",
                icon = Icons.Rounded.SportsEsports,
                navigationIcon = {
                    if (onBack != null) {
                        CortexIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = NavBarClearance)) }
    ) { padding ->
        GamesContent(state, actions, Modifier.padding(padding))
    }

    // The pickers show each game with its picture (13.4, P1).
    val arts = remember(state.rows) { state.rows.associate { it.page.path to it.art } }
    if (picking) {
        GamePickerDialog(
            pages = state.pages,
            arts = arts,
            lastPlayed = state.lastPlayedPath,
            offerEarlier = true,
            onPick = { page, minutesAgo ->
                picking = false
                viewModel.start(page, minutesAgo)
            },
            onDismiss = { picking = false }
        )
    }
    linking?.let { title ->
        GamePickerDialog(
            title = "Link \"${title.name}\" to",
            onPick = { page, _ ->
                linking = null
                viewModel.link(title, page)
            },
            onDismiss = { linking = null }
        )
    }
    if (state.askAccess) {
        GameNightAccessDialog(
            onGrant = {
                viewModel.dismissAccess()
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onDismiss = viewModel::dismissAccess
        )
    }
}

/** What the tab's content does on a tap: [GamesContent] holds no state of its own beyond its folds, so a device test draws it. */
internal class GamesActions(
    val onOpenGame: (String) -> Unit = {},
    val onOpenMap: (String) -> Unit = {},
    val onOpenPsn: () -> Unit = {},
    val onOpenXbox: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onKeepPlaying: () -> Unit = {},
    /** "Link to a page": the page picker opens. */
    val onLink: (UnlinkedTitle) -> Unit = {},
    /** "Link to <page>", one tap to the page the title looks like (13.4). */
    val onLinkTo: (UnlinkedTitle, GamePage) -> Unit = { _, _ -> },
    val onCreatePage: (UnlinkedTitle) -> Unit = {},
    val onHide: (UnlinkedTitle) -> Unit = {},
    val onUnhide: (UnlinkedTitle) -> Unit = {},
    /** A page that's gone: the page picker opens for its typed sessions. */
    val onLinkOrphan: (OrphanedPage) -> Unit = {}
)

/** The tab's lazy keys and test tags (13.4): each item's key is unique across the grid, and the device test scrolls to one. */
internal object GamesTags {
    const val GRID = "games-grid"
    const val NOW = "now"
    const val CAROUSEL = "carousel"
    const val ORPHANS = "orphans"

    fun heading(group: GameGroup): String = "h:${group.name}"

    fun page(path: String): String = "page:$path"

    fun fold(console: GameConsole): String = "fold:${console.name}"

    fun hiddenFold(console: GameConsole): String = "hidden:${console.name}"

    fun unlinked(title: UnlinkedTitle): String = "u:${title.console.name}:${title.titleId}"

    fun hiddenRow(title: UnlinkedTitle): String = "hid:${title.console.name}:${title.titleId}"

    fun orphan(orphan: OrphanedPage): String = "o:${orphan.oldPath}"
}

/**
 * The tab's content (13.4, P2): one lazy grid of covers, about 3 a row on a phone and 6 to 7 from
 * 840 dp, built only as it scrolls into view (the review's ui #7). Full-line items, in order: the
 * read-only notice, the game being played as a wide card (or Start a session), the console lines and
 * links.json's notice, the loading or empty state, each group under its count (Playing a carousel,
 * the others cells), the console folds (rows leaving at once on Hide), the Hidden folds and the
 * typed sessions of a page that's gone. The margins scroll with the grid; the floating bar still
 * hides on scroll (MainScreen's nested scroll).
 */
@Composable
internal fun GamesContent(state: GamesUiState, actions: GamesActions, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val toggle: (String) -> Unit = { key -> open = if (key in open) open - key else open + key }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // A readable column on a wide window: centred at ContentMaxWidth, its margins inside the grid.
        val side = max(16.dp, (maxWidth - ContentMaxWidth) / 2 + 16.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(GamePictureSizes.CellMin),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(start = side, top = 16.dp, end = side, bottom = NavBarClearance),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // The rows of a fold are one segmented list, 2 dp apart; every other block (and each row
            // of cells) keeps BLOCK_PAD below it, so blocks stand 16 dp apart as before.
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            modifier = Modifier.fillMaxSize().testTag(GamesTags.GRID)
        ) {
            if (state.readOnly) {
                item(key = "read-only", span = FULL_LINE) { ReadOnlyNotice(Modifier.padding(bottom = BLOCK_PAD)) }
            }

            val running = state.running
            if (running != null) {
                item(key = GamesTags.NOW, span = FULL_LINE) {
                    Box(Modifier.padding(bottom = BLOCK_PAD)) { NowPlayingCard(state, running, actions) }
                }
            } else if (!state.readOnly && state.pages.isNotEmpty()) {
                item(key = "start", span = FULL_LINE) {
                    Box(Modifier.padding(bottom = BLOCK_PAD)) {
                        Button(onClick = actions.onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("Start a session", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }

            // The console lines and links.json's notice, one block.
            if (state.psn.configured || state.xbox.configured || state.linksUnreadable) {
                item(key = "lines", span = FULL_LINE) {
                    Column(Modifier.padding(bottom = BLOCK_PAD), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.psn.configured) PsnLineRow(state.psn, state.today, actions.onOpenPsn)
                        if (state.xbox.configured) XboxLineRow(state.xbox, state.today, actions.onOpenXbox)
                        if (state.linksUnreadable) LinksNotice()
                    }
                }
            }

            if (state.loading) {
                item(key = "loading", span = FULL_LINE) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            } else if (state.pages.isEmpty()) {
                item(key = "empty", span = FULL_LINE) {
                    EmptyHint(
                        "No game pages yet. A note with game_template in its frontmatter shows here.",
                        modifier = Modifier.padding(bottom = BLOCK_PAD)
                    )
                }
            }

@Composable
private fun PsnLineRow(psn: PsnLine, onOpenPsn: () -> Unit) {
    if (!psn.configured) return
    val status = psn.status
    val text = when {
        !psn.connected -> "Connect PlayStation to bring play time and trophies"
        status == null -> "PSN connected · first sync tonight at 23:40"
        status.ok -> "PSN ✓ ${clock(status.atMillis)}" + (psn.expiresInDays?.let { " · token expires in $it days" } ?: "")
        status.kind == PsnException.Kind.TOKEN_EXPIRED || status.kind == PsnException.Kind.NPSSO_REJECTED -> "PSN paused: paste a new token"
        status.kind == PsnException.Kind.CLIENT_REJECTED -> "PSN paused: PlayStation refused this build's client"
        else -> "PSN: last sync failed (${status.message.ifBlank { "unknown error" }})"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (status != null && !status.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPsn).padding(vertical = 4.dp)
    )
}

/** The Xbox line (13.3), in PSN's style: hidden in a build without the Xbox app; a tap opens its settings. */
@Composable
private fun XboxLineRow(xbox: XboxLine, onOpenXbox: () -> Unit) {
    if (!xbox.configured) return
    val status = xbox.status
    val text = when {
        !xbox.connected -> "Connect Xbox to bring play time and achievements"
        status == null -> "Xbox connected · first sync tonight at 23:40"
        status.ok -> "Xbox ✓ ${clock(status.atMillis)}"
        status.needsSignIn -> "Xbox paused: sign in again"
        status.kind == XboxException.Kind.CLIENT_REJECTED -> "Xbox paused: Microsoft refused this build's app"
        status.kind == XboxException.Kind.ACCOUNT_REFUSED -> "Xbox paused: ${status.message.ifBlank { "Microsoft refused the account" }}"
        else -> "Xbox: last sync failed (${status.message.ifBlank { "unknown error" }})"
    }
    // A status kept from before a disconnect never colours the Connect line.
    val failed = xbox.connected && status != null && !status.ok
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenXbox).padding(vertical = 4.dp)
    )
}

            // 13.4: typed sessions of a page renamed or deleted on the desktop, linked to another page from here.
            if (state.orphans.isNotEmpty()) {
                item(key = GamesTags.ORPHANS, span = FULL_LINE) {
                    FoldHeader(
                        text = "Typed sessions of a page that's gone (${state.orphans.size})",
                        open = GamesTags.ORPHANS in open,
                        onToggle = { toggle(GamesTags.ORPHANS) },
                        modifier = Modifier.testTag(GamesTags.ORPHANS)
                    )
                }
                if (GamesTags.ORPHANS in open) {
                    itemsIndexed(state.orphans, key = { _, orphan -> GamesTags.orphan(orphan) }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, orphan ->
                        OrphanRow(
                            orphan = orphan,
                            shape = segmentShape(index, state.orphans.size),
                            today = state.today,
                            readOnly = state.readOnly,
                            onLink = { actions.onLinkOrphan(orphan) },
                            modifier = Modifier.animateItem().segmentEnd(index, state.orphans.size)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The game being played (13.4, P2): its square cover cropped wide, the title and start on a tonal
 * pill over it with Stop (a writing device), then "Still playing?" and Keep playing while asked.
 */
@Composable
fun GamePickerDialog(
    pages: List<GamePage>,
    onPick: (GamePage, Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Start a session",
    lastPlayed: String? = null,
    offerEarlier: Boolean = false
) {
    var query by rememberSaveable { mutableStateOf("") }
    var minutesAgo by rememberSaveable { mutableIntStateOf(0) }
    val ordered = remember(pages, lastPlayed) {
        pages.sortedWith(
            compareBy({ it.path != lastPlayed }, { it.state != GameState.PLAYING }, { it.state != GameState.NEXT }, { it.title.lowercase() })
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The games being played (13.2, Material 3 Expressive): a multi-browse carousel of big tiles; 13.4:
 * each tile the game's cover over its tone, the title and facts on a tonal pill in that tone, fading
 * as the tile narrows so nothing is cut mid-word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayingCarousel(rows: List<GameRow>, today: LocalDate, onOpen: (String) -> Unit) {
    val carousel = rememberCarouselState { rows.size }
    HorizontalMultiBrowseCarousel(
        state = carousel,
        preferredItemWidth = 260.dp,
        itemSpacing = 10.dp,
        modifier = Modifier.fillMaxWidth().height(CAROUSEL_HEIGHT)
    ) { index ->
        // A list that just shrank may still be asked for its old last index once.
        val row = rows.getOrNull(index)
        if (row != null) {
            val tone = toneFor(row.art.key)
            val info = carouselItemDrawInfo
            val facts = listOfNotNull(
                row.page.template.label.takeIf { row.page.template.key.isNotEmpty() },
                row.hours?.let { hours(it) },
                row.lastPlayed?.let { "played ${dayLabel(it, today)}" }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .maskClip(MaterialTheme.shapes.extraLarge)
                    .clickable(role = Role.Button) { onOpen(row.page.path) }
            ) {
                GamePicture(row.art, Modifier.fillMaxSize().clearAndSetSemantics {}, shape = null, contentDescription = null)
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = tone.container,
                    contentColor = tone.content,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .graphicsLayer {
                            val span = (info.maxSize - info.minSize).coerceAtLeast(1f)
                            alpha = ((info.size - info.minSize) / span).coerceIn(0f, 1f)
                        }
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            row.page.title,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (facts.isNotEmpty()) {
                            Text(
                                facts.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (pages.size > SEARCH_FROM) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Find a game") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (shown.isEmpty()) Text("No game matches.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(shown, key = { it.path }) { page ->
                        ListItem(
                            supportingContent = { Text(if (page.path == lastPlayed) "Played last · ${page.state.label}" else page.state.label) },
                            modifier = Modifier.clickable { onPick(page, minutesAgo) }
                        ) {
                            Text(page.title)
                        }
                        OutlinedButton(onClick = { actions.onCreatePage(title) }, shapes = ButtonDefaults.shapes()) { Text("Create a page") }
                    } else {
                        OutlinedButton(onClick = { actions.onLink(title) }, shapes = ButtonDefaults.shapes()) { Text("Link to a page") }
                    }
                    TextButton(onClick = { actions.onHide(title) }, shapes = ButtonDefaults.shapes()) { Text("Hide") }
                }
            }
        }
    }
}

/** A title the owner hid (13.4), with Unhide on a writing device. */
@Composable
private fun HiddenRow(
    title: UnlinkedTitle,
    shape: Shape,
    today: LocalDate,
    readOnly: Boolean,
    onUnhide: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TitleLine(title.art, title.name, facts(title.hours, title.lastPlayed, today)) }
            if (!readOnly) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onUnhide, shapes = ButtonDefaults.shapes()) { Text("Unhide") }
            }
        }
    }
}

/** Typed sessions of a page that's gone (13.4): their old name, hours and count, with "Link to a page" on a writing device. */
@Composable
private fun OrphanRow(
    orphan: OrphanedPage,
    shape: Shape,
    today: LocalDate,
    readOnly: Boolean,
    onLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    val facts = listOfNotNull(
        hours(orphan.minutes / 60.0),
        if (orphan.sessions == 1) "1 session" else "${orphan.sessions} sessions",
        orphan.lastDate?.let { "played ${dayLabel(it, today)}" }
    ).joinToString(" · ")
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TitleLine(GameArtRules.initialsOnly(orphan.name), orphan.name, facts)
            if (!readOnly) {
                OutlinedButton(onClick = onLink, shapes = ButtonDefaults.shapes()) { Text("Link to a page") }
            }
        }
    }
}

/** A fold row's head: the 40 dp picture (13.4, P1), the name, and its facts under it. */
@Composable
fun GameNightAccessDialog(onGrant: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game night needs Do Not Disturb access") },
        text = {
            Text(
                "On a Perso evening, a game session turns on the app's own \"Game night\" mode, with the exceptions " +
                    "you choose in Settings › Modes. Allow PixelBrain in Do Not Disturb access to use it. The session runs either way."
            )
        },
        confirmButton = { TextButton(onClick = onGrant, shapes = ButtonDefaults.shapes()) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Not now") } }
    )
}

/** "4.5 h · played yesterday": the hours only when the console says (an Xbox title may not report its minutes). */
private fun facts(played: Double?, lastPlayed: LocalDate?, today: LocalDate): String? =
    listOfNotNull(played?.let { hours(it) }, lastPlayed?.let { "played ${dayLabel(it, today)}" })
        .joinToString(" · ")
        .ifEmpty { null }

/** A grid item across the whole line: everything but the cells. */
private val FULL_LINE: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** The grid's own spacing: two rows of a fold, one segmented list. */
private val ROW_GAP = 2.dp

/** Below every other block and each row of cells: with [ROW_GAP], blocks stand 16 dp apart. */
private val BLOCK_PAD = 14.dp

/** The last row of a fold keeps a block's space below it; the others sit [ROW_GAP] apart. */
private fun Modifier.segmentEnd(index: Int, count: Int): Modifier = if (index == count - 1) padding(bottom = BLOCK_PAD) else this

/** The cells' content type: the grid reuses one cell's composition for another. */
private const val CELL = "cell"

private val CAROUSEL_HEIGHT = 230.dp

internal fun hours(value: Double): String = when {
    value < 1.0 -> "${(value * 60).toInt()} min"
    value < 10.0 -> String.format(Locale.ENGLISH, "%.1f h", value)
    else -> "${value.toInt()} h"
}

internal fun clock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
