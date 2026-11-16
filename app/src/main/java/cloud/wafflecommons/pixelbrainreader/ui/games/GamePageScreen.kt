package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.ManualSessions
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimeline
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySection
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.main.MarkwonContent
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.LocalTime
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance

/**
 * A game's page (RFC-009 §4.2): its state, hours and trophies, the checklists of its note (a trophy's
 * line stays ??? until reached or revealed), its sessions, the notes of its folder, and Ask Claude.
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
    var reveal by rememberSaveable { mutableStateOf(false) }
    // One hidden item revealed at a time (RFC-009 §4.2: "??? until reached or tapped"); Reveal shows them all.
    var revealedKeys by rememberSaveable { mutableStateOf("") }
    fun shown(key: String) = reveal || key in revealedKeys.split('\n')
    fun show(key: String) {
        revealedKeys = if (revealedKeys.isEmpty()) key else revealedKeys + "\n" + key
    }
    val page = state.page
    // 13.1: the session dialogs ("Add" = a forgotten session; a tap on a manual session fixes it),
    // the console's play time and "Started earlier".
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPlayedBefore by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    FormScaffold(title = page?.title ?: "Game", onBack = onBack, icon = Icons.Rounded.SportsEsports) {
        if (page == null) {
            if (!state.loading) EmptyHint("This game page is gone: it was moved or deleted.")
            return@FormScaffold
        }
        if (state.readOnly) ReadOnlyNotice()

        SectionCard(
            title = page.template.label,
            icon = Icons.Rounded.SportsEsports,
            subtitle = listOfNotNull(
                state.hours?.let { hours(it) },
                if (state.trophiesTotal > 0) "${state.trophiesEarned} of ${state.trophiesTotal} trophies" else null,
                page.started?.let { "started ${day(it)}" },
                page.finished?.let { "finished ${day(it)}" }
            ).joinToString(" · ").ifEmpty { null }
        ) {
            state.iconUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameState.entries.forEach { s ->
                    FilterChip(
                        selected = page.state == s,
                        onClick = { if (page.state != s) viewModel.setState(s) },
                        enabled = !state.readOnly,
                        label = { Text(s.label) }
                    )
                }
            }
            if (!state.readOnly) {
                TextButton(onClick = { editingPlayedBefore = true }, shapes = ButtonDefaults.shapes()) {
                    Text(page.playedBefore?.let { "Console play time before the app: ${hours(it)}" } ?: "Add the console's play time")
                }
            }
        }

        // The page itself, without what the cards below show (RFC-009 §4.2: the app renders it).
        if (state.prose.isNotBlank()) {
            SectionCard(title = "Page", icon = Icons.AutoMirrored.Rounded.Article) {
                MarkwonContent(
                    content = state.prose,
                    onWikiLinkClick = { link -> onOpenNote("[[" + link.removePrefix("[[").removeSuffix("]]") + "]]") }
                )
            }
        }

        if (state.trophies.isNotEmpty()) {
            SectionCard(
                title = "Trophies",
                icon = Icons.Rounded.EmojiEvents,
                subtitle = "${state.trophiesEarned} of ${state.trophiesTotal} · the others stay hidden until you reveal them",
                trailing = { TextButton(onClick = { reveal = !reveal }, shapes = ButtonDefaults.shapes()) { Text(if (reveal) "Hide" else "Reveal") } }
            ) {
                state.trophies.forEach { line ->
                    val earned = line.earnedOn
                    val key = "t:${line.set}#${line.trophy.id}"
                    val hidden = earned == null && !shown(key)
                    val name = if (hidden) "???" else line.trophy.name.ifBlank { "Trophy ${line.trophy.id}" }
                    Text(
                        "${TrophySection.gradeIcon(line.trophy.grade)}  $name" + (earned?.let { "  ·  ${day(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (earned != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = hidden) { show(key) }
                    )
                }
            }
        }

        state.checklists.forEach { section ->
            SectionCard(title = section.heading.ifBlank { "Checklist" }, icon = Icons.Rounded.Checklist) {
                section.items.forEach { item ->
                    val key = "c:${item.line}"
                    val hidden = item.masked && !shown(key)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = hidden) { show(key) }
                    ) {
                        Checkbox(checked = item.done, onCheckedChange = { viewModel.toggle(item.line) }, enabled = !state.readOnly)
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
            state.sessions.take(SESSIONS_SHOWN).forEach { record ->
                val manual = record.source == GameSessionRecord.SOURCE_MANUAL
                val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
                Text(
                    listOfNotNull(date?.let { day(it) }, record.start, GameTimeline.duration(record.minutes), if (manual) null else "PlayStation")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = manual && !state.readOnly) { editingId = record.id }
                        .padding(vertical = 6.dp)
                )
            }
            if (state.history.isNotEmpty()) {
                Text("Earlier, from your Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                state.history.take(30).forEach { entry ->
                    Text("${day(entry.date)} ${entry.time} · ${entry.text}", style = MaterialTheme.typography.bodySmall)
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
        Spacer(Modifier.height(88.dp))
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
    }

    if (adding) {
        GameSessionDialog(
            initial = null,
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

/**
 * Adds a session the owner forgot to start ([initial] null: day, start, length) or fixes one
 * (its start and length; the day stays). Delete asks a second tap. 13.1.
 */
@Composable
private fun GameSessionDialog(
    initial: GameSessionRecord?,
    onSave: (LocalDate, LocalTime, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now()
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
                    Text("Day: ${day(picked)}")
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
                    "The hours your PS5 shows for this game before you used the app. Your sessions add up on top; once PlayStation Network works, its own total replaces both.",
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GameToolbar(
    running: Boolean,
    readOnly: Boolean,
    maps: List<cloud.wafflecommons.pixelbrainreader.data.games.GameMap>,
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

