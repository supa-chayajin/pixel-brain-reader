@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSection
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameMap
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimeline
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.ManualSessions
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySection
import cloud.wafflecommons.pixelbrainreader.ui.components.CardGrid
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.main.MarkwonContent
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.isWideLayout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A game's page (RFC-009 §4.2): its state, hours and trophies, the checklists of its note (a trophy's
 * line stays ??? until reached or revealed), its sessions, the notes of its folder, and Ask Claude.
 * 13.3: its maps, changed here (ME1), and Xbox's achievements beside the trophies (XB2: side by side
 * on a wide window, stacked on the phone). 13.4: the game's picture beside its facts (P3), the
 * owner's own one chosen or removed here (P5), and every day label counted from the state's day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamePageScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onAskClaude: (root: String?, text: String) -> Unit,
    onOpenMap: (path: String, index: Int) -> Unit = { _, _ -> },
    viewModel: GamePageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var reveal by rememberSaveable { mutableStateOf(false) }
    // One hidden item revealed at a time (RFC-009 §4.2: "??? until reached or tapped"); Reveal shows them all.
    var revealedKeys by rememberSaveable { mutableStateOf("") }
    val revealed = remember(revealedKeys) { revealedKeys.split('\n').filter { it.isNotEmpty() }.toSet() }
    fun shown(key: String) = reveal || key in revealed
    fun show(key: String) {
        revealedKeys = if (revealedKeys.isEmpty()) key else revealedKeys + "\n" + key
    }
    val page = state.page
    // 13.1: the session dialogs ("Add" = a forgotten session; a tap on a manual session fixes it),
    // the console's play time and "Started earlier".
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPlayedBefore by rememberSaveable { mutableStateOf(false) }
    // 13.3 (ME1): the maps card's dialogs. A map is named by its address, never by its place.
    var addingMap by rememberSaveable { mutableStateOf(false) }
    var changingMap by rememberSaveable { mutableStateOf<String?>(null) }
    var removingMap by rememberSaveable { mutableStateOf<String?>(null) }
    // 13.4 (P5): the owner's own picture, from the system's photo picker (no permission asked).
    var removingPicture by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setCover(uri)
    }
    // A map that left the page meanwhile (a sync, a desktop edit) takes its dialog along.
    LaunchedEffect(page?.maps) {
        val urls = page?.maps?.map { it.url } ?: return@LaunchedEffect
        val changing = changingMap
        if (changing != null && changing !in urls) changingMap = null
        val removing = removingMap
        if (removing != null && removing !in urls) removingMap = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
    FormScaffold(title = page?.title ?: "Game", onBack = onBack, icon = Icons.Rounded.SportsEsports) {
        if (page == null) {
            if (!state.loading) EmptyHint("This game page is gone: it was moved or deleted.")
            return@FormScaffold
        }
        if (state.readOnly) ReadOnlyNotice()

        GameHeaderCard(
            page = page,
            state = state,
            onSetState = viewModel::setState,
            onEditPlayedBefore = { editingPlayedBefore = true },
            onChoosePicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemovePicture = { removingPicture = true }
        )

        // The page itself, without what the cards below show (RFC-009 §4.2: the app renders it).
        if (state.prose.isNotBlank()) {
            SectionCard(title = "Page", icon = Icons.AutoMirrored.Rounded.Article) {
                MarkwonContent(
                    content = state.prose,
                    onWikiLinkClick = { link -> onOpenNote("[[" + link.removePrefix("[[").removeSuffix("]]") + "]]") }
                )
            }
        }

        MapsCard(
            maps = page.maps,
            unreadable = state.unreadableMaps,
            readOnly = state.readOnly,
            onOpen = { index -> onOpenMap(page.path, index) },
            onAdd = { addingMap = true },
            onChange = { map -> changingMap = map.url },
            onMakeMain = { map -> viewModel.makeMainMap(map.url) },
            onRemove = { map -> removingMap = map.url }
        )

        AwardCards(
            state = state,
            reveal = reveal,
            onToggleReveal = { reveal = !reveal },
            shown = { key -> shown(key) },
            onShow = { key -> show(key) }
        )

        state.checklists.forEach { section ->
            // 13.4: a spoiler shown here can be hidden again, like the awards' ones.
            val showingHidden = section.items.any { it.masked && shown(checklistKey(it.index)) }
            SectionCard(
                title = section.heading.ifBlank { "Checklist" },
                icon = Icons.Rounded.Checklist,
                trailing = {
                    if (showingHidden) TextButton(onClick = { hideAll() }, shapes = ButtonDefaults.shapes()) { Text("Hide") }
                }
            ) {
                section.items.forEach { item ->
                    // 13.4 (the audit's checklist-duplicate-line-tick): keyed and ticked by the line's
                    // own place, so a line written twice reveals and ticks only the copy tapped.
                    val key = checklistKey(item.index)
                    val hidden = item.masked && !shown(key)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = hidden, onClickLabel = "Reveal") { show(key) }
                    ) {
                        Checkbox(checked = item.done, onCheckedChange = { viewModel.toggle(item) }, enabled = !state.readOnly)
                        Text(if (hidden) "???" else item.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        SectionCard(
            title = "Sessions",
            icon = Icons.Rounded.History,
            subtitle = if (!state.readOnly && state.sessions.any { it.source == GameSessionRecord.SOURCE_MANUAL }) "Tap a session to fix it" else null,
            trailing = {
                if (!state.readOnly) TextButton(onClick = { adding = true }, shapes = ButtonDefaults.shapes()) { Text("Add") }
            }
        ) {
            if (state.sessions.isEmpty() && state.history.isEmpty()) {
                EmptyHint("No session yet: start one here, or add one you forgot to start.")
            }
            // 13.4: rows of at least 48 dp, side by side, so a tap lands on the session aimed at.
            Column {
                state.sessions.take(SESSIONS_SHOWN).forEach { record ->
                    val manual = record.source == GameSessionRecord.SOURCE_MANUAL
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TOUCH_ROW)
                            .clickable(enabled = manual && !state.readOnly, onClickLabel = "Fix this session") { editingId = record.id }
                    ) {
                        Text(sessionLine(record, state.today), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.history.isNotEmpty()) {
                Text("Earlier, from your Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                state.history.take(30).forEach { entry ->
                    Text("${dayLabel(entry.date, state.today)} ${entry.time} · ${entry.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard(title = "Notes", icon = Icons.AutoMirrored.Rounded.Article) {
            TextButton(onClick = { onOpenNote(page.path) }, shapes = ButtonDefaults.shapes()) { Text("Open this game's page") }
            state.folderNotes.forEach { note ->
                TextButton(onClick = { onOpenNote(note) }, shapes = ButtonDefaults.shapes()) { Text(note.substringAfterLast('/').removeSuffix(".md")) }
            }
        }
        // Room for the floating toolbar under the last card.
        Spacer(Modifier.height(TOOLBAR_ROOM))
    }
    // 13.2, Material 3 Expressive: the game's actions in a floating toolbar, play/stop as its FAB.
    page?.let { game ->
        GameToolbar(
            running = state.running?.page == game.path,
            readOnly = state.readOnly,
            maps = game.maps,
            onStart = { minutes -> viewModel.start(minutes) },
            onStop = viewModel::stop,
            onOpenMap = { index -> onOpenMap(game.path, index) },
            onAsk = { onAskClaude(state.askProject, GamePages.askText(game)) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = NavBarClearance)
        )
    }
    // 13.3: what a map edit did, above the floating toolbar (the scaffold's own host sits under it).
    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = NavBarClearance + TOOLBAR_ROOM))
    }

    if (adding) {
        GameSessionDialog(
            initial = null,
            today = state.today,
            onSave = { date, start, minutes ->
                adding = false
                viewModel.addSession(date, start, minutes)
            },
            onDelete = null,
            onDismiss = { adding = false }
        )
    }
    state.sessions.firstOrNull { it.id == editingId }?.let { record ->
        GameSessionDialog(
            initial = record,
            today = state.today,
            onSave = { _, start, minutes ->
                editingId = null
                viewModel.editSession(record, start, minutes)
            },
            onDelete = {
                editingId = null
                viewModel.deleteSession(record)
            },
            onDismiss = { editingId = null }
        )
    }
    if (editingPlayedBefore && page != null) {
        PlayedBeforeDialog(
            initial = page.playedBefore,
            onSave = { text ->
                editingPlayedBefore = false
                viewModel.setPlayedBefore(text)
            },
            onDismiss = { editingPlayedBefore = false }
        )
    }

    // 13.3 (ME1): the maps card's dialogs; the repository reads the page from disk and checks again.
    if (addingMap && page != null && !state.readOnly) {
        MapEditDialog(
            title = "Add a map",
            initialUrl = "",
            initialLabel = null,
            confirmLabel = "Add",
            onSave = { url, label ->
                addingMap = false
                viewModel.addMap(url, label)
            },
            onDismiss = { addingMap = false }
        )
    }
    changingMap?.let { url -> page?.maps?.firstOrNull { it.url == url } }?.takeIf { !state.readOnly }?.let { map ->
        MapEditDialog(
            title = "Change this map",
            initialUrl = map.url,
            initialLabel = map.given,
            onSave = { url, label ->
                changingMap = null
                viewModel.changeMap(map.url, url, label)
            },
            onDismiss = { changingMap = null }
        )
    }
    removingMap?.let { url -> page?.maps?.firstOrNull { it.url == url } }?.takeIf { !state.readOnly }?.let { map ->
        AlertDialog(
            onDismissRequest = { removingMap = null },
            title = { Text("Remove this map?") },
            text = { Text("${map.label} · ${map.site} leaves this game's maps. The site itself isn't touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingMap = null
                        viewModel.removeMap(map.url)
                    },
                    shapes = ButtonDefaults.shapes()
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removingMap = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
        )
    }

    // 13.4 (P5): the owner's own picture goes; the console's shows again, or the initials.
    if (removingPicture && state.hasOwnPicture && !state.readOnly) {
        AlertDialog(
            onDismissRequest = { removingPicture = false },
            title = { Text("Remove this picture?") },
            text = { Text("The console's picture shows again, or the initials.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingPicture = false
                        viewModel.removeCover()
                    },
                    shapes = ButtonDefaults.shapes()
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removingPicture = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
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

/** The sessions the page lists (newest first); the Timeline lines go on below them. */
private const val SESSIONS_SHOWN = 60

/** The floating toolbar's room under the last card, and the snackbar's lift above it. */
private val TOOLBAR_ROOM = 88.dp

/** A row's least height, the size of a touch target (13.4: rows a tap can't miss). */
private val TOUCH_ROW = 48.dp

/** A checklist line's reveal key: its place in the page (13.4), never its text. */
private fun checklistKey(index: Int): String = "c:$index"

private fun trophyKey(line: TrophyLine): String = "t:${line.set}#${line.trophy.id}"

private fun achievementKey(line: AchievementLine): String = "a:${line.titleId}#${line.record.id}"

/** Which console measured a session (13.3): none for a manual one. */
private fun consoleOf(record: GameSessionRecord): String? = when (record.source) {
    GameSessionRecord.SOURCE_MANUAL -> null
    GameSessionRecord.SOURCE_XBOX -> GameConsole.XBOX.label
    else -> GameConsole.PSN.label
}

/**
 * A session's line on the page: "today · 21:00 · 2 h 35 · PlayStation". 13.4 (G1): play measured
 * across a gap no console dates says since when instead, "since 20 Nov · 6 h · PlayStation" (it
 * counts in the hours and has no day of its own).
 */
private fun sessionLine(record: GameSessionRecord, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    val duration = GameTimeline.duration(record.minutes)
    val since = record.since?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
    if (since != null) return listOfNotNull("since ${dayLabel(since, today)}", duration, consoleOf(record)).joinToString(" · ")
    val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
    return listOfNotNull(date?.let { dayLabel(it, today) }, record.start, duration, consoleOf(record)).joinToString(" · ")
}

/**
 * The game's first card (13.4, P3): its square picture in the game's own shape (the owner's, else a
 * console's, else its initials, P5–P7), its facts beside it (hours, trophies, achievements, dates),
 * then the state chips, the console's play time and, on a writing device, the owner's own picture:
 * choose one with the system's photo picker, or remove it. A read-only device only shows the picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameHeaderCard(
    page: GamePage,
    state: GamePageUiState,
    onSetState: (GameState) -> Unit,
    onEditPlayedBefore: () -> Unit,
    onChoosePicture: () -> Unit,
    onRemovePicture: () -> Unit
) {
    val art = state.art ?: GameArtRules.initialsOnly(page.title)
    val facts = listOfNotNull(
        state.hours?.let { "${hours(it)} played" },
        if (state.trophiesTotal > 0) "${state.trophiesEarned} of ${state.trophiesTotal} trophies" else null,
        if (state.achievementsTotal > 0) {
            "${state.achievementsEarned} of ${state.achievementsTotal} achievements · ${state.gamerscoreEarned} of ${state.gamerscoreTotal} G"
        } else {
            null
        },
        page.started?.let { "started ${dayLabel(it, state.today)}" },
        page.finished?.let { "finished ${dayLabel(it, state.today)}" }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                GamePicture(art, Modifier.size(if (isWideLayout()) GamePictureSizes.PageWide else GamePictureSizes.Page))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(page.template.label, style = MaterialTheme.typography.titleLargeEmphasized)
                    facts.forEach { fact ->
                        Text(fact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameState.entries.forEach { s ->
                    FilterChip(
                        selected = page.state == s,
                        onClick = { if (page.state != s) onSetState(s) },
                        enabled = !state.readOnly,
                        label = { Text(s.label) }
                    )
                }
            }
            if (!state.readOnly) {
                TextButton(onClick = onEditPlayedBefore, shapes = ButtonDefaults.shapes()) {
                    Text(
                        page.playedBefore?.let { typed ->
                            "Console play time before the app: ${hours(typed)}" +
                                if (state.measured) " (a console's own total replaces it)" else ""
                        } ?: "Add the console's play time"
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onChoosePicture, enabled = !state.coverBusy, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Choose a picture")
                    }
                    if (state.hasOwnPicture) {
                        OutlinedButton(onClick = onRemovePicture, enabled = !state.coverBusy, shapes = ButtonDefaults.shapes()) {
                            Text("Remove the picture")
                        }
                    }
                }
                if (state.coverBusy) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The page's maps (13.3, ME1): label · site, the main one first. Add, and per map Change, Make
 * main and Remove; a tap opens it in the map browser. A read-only device lists them, tappable,
 * with the notice and no controls, and shows no card for a game without a map (the page has its
 * notice already).
 */
@Composable
private fun MapsCard(
    maps: List<GameMap>,
    unreadable: Int,
    readOnly: Boolean,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onChange: (GameMap) -> Unit,
    onMakeMain: (GameMap) -> Unit,
    onRemove: (GameMap) -> Unit
) {
    if (readOnly && maps.isEmpty()) return
    SectionCard(
        title = "Maps",
        icon = Icons.Rounded.Map,
        subtitle = listOfNotNull(
            if (maps.isNotEmpty()) "The first one is the main map" else null,
            when {
                unreadable == 0 -> null
                maps.isNotEmpty() -> "plus $unreadable the app can't open, kept in the note"
                // Nothing for "plus" to add to.
                else -> "$unreadable ${if (unreadable == 1) "address" else "addresses"} the app can't open, kept in the note"
            }
        ).joinToString(" · ").ifEmpty { null },
        trailing = {
            if (!readOnly) TextButton(onClick = onAdd, shapes = ButtonDefaults.shapes()) { Text("Add") }
        }
    ) {
        if (readOnly) ReadOnlyNotice()
        if (maps.isEmpty()) {
            EmptyHint("No map yet. Add a map's address here, or share a map page to the app from your browser.")
        }
        maps.forEachIndexed { index, map ->
            MapRow(
                map = map,
                main = index == 0,
                readOnly = readOnly,
                onOpen = { onOpen(index) },
                onChange = { onChange(map) },
                onMakeMain = if (index > 0) ({ onMakeMain(map) }) else null,
                onRemove = { onRemove(map) }
            )
        }
    }
}

/** One map of the card: a tap opens it; the menu changes it ([onMakeMain] null for the main one). */
@Composable
private fun MapRow(
    map: GameMap,
    main: Boolean,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onChange: () -> Unit,
    onMakeMain: (() -> Unit)?,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = "Open the map", onClick = onOpen)
                .padding(horizontal = 4.dp, vertical = 10.dp)
        ) {
            Text(
                "${map.label} · ${map.site}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (main) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "Main",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (!readOnly) {
            Box {
                IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${map.label}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Change") },
                        onClick = {
                            menu = false
                            onChange()
                        }
                    )
                    if (onMakeMain != null) {
                        DropdownMenuItem(
                            text = { Text("Make main") },
                            onClick = {
                                menu = false
                                onMakeMain()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menu = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

/** The console awards' cards (13.3, XB2). */
private enum class AwardCard { TROPHIES, ACHIEVEMENTS }

/**
 * The Trophies and Achievements cards: side by side on a wide window and stacked on the phone
 * ([CardGrid]) when the page has both, the one alone full width otherwise. One Reveal for both.
 */
@Composable
private fun AwardCards(
    state: GamePageUiState,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    shown: (String) -> Boolean,
    onShow: (String) -> Unit
) {
    val cards = listOfNotNull(
        AwardCard.TROPHIES.takeIf { state.trophies.isNotEmpty() },
        AwardCard.ACHIEVEMENTS.takeIf { state.achievements.isNotEmpty() }
    )
    val card: @Composable (AwardCard, Modifier) -> Unit = { which, modifier ->
        when (which) {
            AwardCard.TROPHIES -> TrophiesCard(state, reveal, onToggleReveal, shown, onShow, modifier)
            AwardCard.ACHIEVEMENTS -> AchievementsCard(state, reveal, onToggleReveal, shown, onShow, modifier)
        }
    }
    if (cards.size > 1) CardGrid(cards, card) else cards.forEach { card(it, Modifier) }
}

@Composable
private fun RevealButton(reveal: Boolean, onToggleReveal: () -> Unit) {
    TextButton(onClick = onToggleReveal, shapes = ButtonDefaults.shapes()) { Text(if (reveal) "Hide" else "Reveal") }
}

@Composable
private fun TrophiesCard(
    state: GamePageUiState,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    shown: (String) -> Boolean,
    onShow: (String) -> Unit,
    modifier: Modifier
) {
    val showingHidden = reveal || state.trophies.any { it.earnedOn == null && shown(trophyKey(it)) }
    SectionCard(
        title = "Trophies",
        modifier = modifier,
        icon = Icons.Rounded.EmojiEvents,
        subtitle = "${state.trophiesEarned} of ${state.trophiesTotal} · the others stay hidden until you reveal them",
        trailing = { RevealButton(reveal, onToggleReveal) }
    ) {
        // 13.4 (the audit's spoiler-rows-small-targets): 48 dp rows side by side, so a slightly-off
        // tap never reveals the neighbour.
        Column {
            state.trophies.forEach { line ->
                val earned = line.earnedOn
                val key = trophyKey(line)
                val hidden = earned == null && !shown(key)
                val name = if (hidden) "???" else line.trophy.name.ifBlank { "Trophy ${line.trophy.id}" }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TOUCH_ROW)
                        .clickable(enabled = hidden, onClickLabel = "Reveal") { onShow(key) }
                ) {
                    Text(
                        "${TrophySection.gradeIcon(line.trophy.grade)}  $name" + (earned?.let { "  ·  ${dayLabel(it, state.today)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (earned != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Xbox's achievements (13.3, XB2): earned first, the others ??? until tapped or revealed. */
@Composable
private fun AchievementsCard(
    state: GamePageUiState,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    shown: (String) -> Boolean,
    onShow: (String) -> Unit,
    modifier: Modifier
) {
    val showingHidden = reveal || state.achievements.any { it.earnedOn == null && shown(achievementKey(it)) }
    SectionCard(
        title = "Achievements",
        modifier = modifier,
        icon = Icons.Rounded.MilitaryTech,
        subtitle = "${state.achievementsEarned} of ${state.achievementsTotal} · ${state.gamerscoreEarned} of ${state.gamerscoreTotal} G" +
            " · the others stay hidden until you reveal them",
        trailing = { RevealButton(reveal, onToggleReveal) }
    ) {
        Column {
            state.achievements.forEach { line ->
                val earned = line.earnedOn
                val key = achievementKey(line)
                val hidden = earned == null && !shown(key)
                val name = if (hidden) "???" else line.record.name.ifBlank { "Achievement ${line.record.id}" }
                val set = if (state.achievementSets > 1 && line.setTitle.isNotBlank()) "  ·  ${line.setTitle}" else ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TOUCH_ROW)
                        .clickable(enabled = hidden, onClickLabel = "Reveal") { onShow(key) }
                ) {
                    Text(
                        "${AchievementSection.ICON}  $name  ·  ${line.record.gamerscore} G" +
                            (earned?.let { "  ·  ${dayLabel(it, state.today)}" } ?: "") + set,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (earned != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Adds a session the owner forgot to start ([initial] null: day, start, length) or fixes one
 * (its start and length; the day stays). Delete asks a second tap. 13.1. [today] is the page's day
 * (13.4: AppClock's, as every label of the page).
 */
@Composable
private fun GameSessionDialog(
    initial: GameSessionRecord?,
    today: LocalDate,
    onSave: (LocalDate, LocalTime, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var date by rememberSaveable { mutableStateOf(initial?.date ?: today.toString()) }
    var start by rememberSaveable { mutableStateOf(initial?.start ?: LocalTime.now().minusHours(1).withSecond(0).withNano(0).format(HHMM)) }
    var hoursText by rememberSaveable { mutableStateOf(((initial?.minutes ?: 60) / 60).toString()) }
    var minutesText by rememberSaveable { mutableStateOf(((initial?.minutes ?: 60) % 60).toString()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picked = runCatching { LocalDate.parse(date) }.getOrNull() ?: today
    val time = runCatching { LocalTime.parse(start) }.getOrNull() ?: LocalTime.NOON
    val minutes = (hoursText.toIntOrNull() ?: 0) * 60 + (minutesText.toIntOrNull() ?: 0)
    val inFuture = picked.isAfter(today) || (picked == today && time.isAfter(LocalTime.now()))
    val valid = minutes in 1..ManualSessions.MAX_MINUTES && !inFuture &&
        (hoursText.isEmpty() || hoursText.toIntOrNull() != null) && (minutesText.isEmpty() || minutesText.toIntOrNull() in 0..59)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a session" else "Fix this session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, shapes = ButtonDefaults.shapes(), enabled = initial == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Day: ${dayLabel(picked, today)}")
                }
                OutlinedButton(onClick = { pickingTime = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Started at $start")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hours") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                when {
                    inFuture -> Text("That start is still to come.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    minutes !in 1..ManualSessions.MAX_MINUTES -> Text("A session lasts 1 minute to 24 hours.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    picked.isBefore(today.minusDays(GamesRepository.PAST_DAYS)) -> Text(
                        "More than a week ago: it counts in your hours, but that day's note isn't edited.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, shapes = ButtonDefaults.shapes(), onClick = { onSave(picked, time, minutes) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }, shapes = ButtonDefaults.shapes()) {
                        Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            }
        }
    )
    if (pickingDate) {
        PickDateDialog(initial = picked, onConfirm = { date = it.toString(); pickingDate = false }, onDismiss = { pickingDate = false })
    }
    if (pickingTime) {
        PickTimeDialog(initial = start, onConfirm = { start = it; pickingTime = false }, onDismiss = { pickingTime = false })
    }
}

/** The play time the console shows for the game, typed once (13.1): hours, `228h30` or `228:30`. */
@Composable
private fun PlayedBeforeDialog(initial: Double?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }.orEmpty()) }
    val valid = text.isBlank() || GamePages.hours(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Console play time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The hours your console shows for this game before you used the app. Your sessions add up on top; " +
                        "once PlayStation Network or Xbox measures the game, the measured total replaces both.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(10) },
                    label = { Text("Hours, e.g. 228 or 228h30") },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) Text("Write hours like 228, 228.5, 228h30 or 228:30.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, shapes = ButtonDefaults.shapes(), onClick = { onSave(text.trim()) }) { Text(if (text.isBlank()) "Clear" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}

private val HHMM: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * The game's actions (13.2): Material 3 Expressive's floating toolbar, play or stop as its vibrant
 * FAB, then "Started earlier", the map and Ask Claude. A read-only device keeps only the map.
 */
@Composable
private fun GameToolbar(
    running: Boolean,
    readOnly: Boolean,
    maps: List<GameMap>,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onOpenMap: (Int) -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier
) {
    var earlierMenu by remember { mutableStateOf(false) }
    var mapMenu by remember { mutableStateOf(false) }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (!readOnly && !running) {
            Box {
                IconButton(onClick = { earlierMenu = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.MoreTime, contentDescription = "Started earlier")
                }
                DropdownMenu(expanded = earlierMenu, onDismissRequest = { earlierMenu = false }) {
                    listOf(10, 20, 30).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("Started $minutes min ago") },
                            onClick = {
                                earlierMenu = false
                                onStart(minutes)
                            }
                        )
                    }
                }
            }
        }
        if (maps.isNotEmpty()) {
            Box {
                IconButton(onClick = { if (maps.size == 1) onOpenMap(0) else mapMenu = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Map, contentDescription = "Open the map")
                }
                DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                    maps.forEachIndexed { index, map ->
                        DropdownMenuItem(
                            text = { Text("${map.label} · ${map.site}") },
                            onClick = {
                                mapMenu = false
                                onOpenMap(index)
                            }
                        )
                    }
                }
            }
        }
        if (!readOnly) {
            IconButton(onClick = onAsk, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.QuestionAnswer, contentDescription = "Ask Claude")
            }
        }
    }
    if (readOnly) {
        if (maps.isEmpty()) return
        androidx.compose.material3.HorizontalFloatingToolbar(
            expanded = true,
            modifier = modifier,
            colors = androidx.compose.material3.FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            content = actions
        )
    } else {
        androidx.compose.material3.HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                androidx.compose.material3.FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { if (running) onStop() else onStart(0) }) {
                    Icon(
                        if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = if (running) "Stop the session" else "Start a session"
                    )
                }
            },
            modifier = modifier,
            colors = androidx.compose.material3.FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            content = actions
        )
    }
}
