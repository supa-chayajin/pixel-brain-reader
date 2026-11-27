@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSection
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionGuard
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnException
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxException
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.ExpressiveBadge
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import coil.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.style.TextOverflow
import cloud.wafflecommons.pixelbrainreader.ui.components.Tone
import cloud.wafflecommons.pixelbrainreader.ui.components.toneFor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItemDefaults

/**
 * The Games tab (RFC-009 §4.1): the library in four groups, the running session, the PSN and Xbox
 * lines (13.3), and each console's titles no page claims. A tap opens the game's page.
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
    var picking by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<UnlinkedTitle?>(null) }

    FormScaffold(title = "Games", icon = Icons.Rounded.SportsEsports, onBack = onBack) {
        if (state.readOnly) ReadOnlyNotice()

        val running = state.running
        if (running != null) {
            val asked = state.runningCheck?.takeIf { it.sessionStart == running.startMillis }
            SectionCard(
                title = state.runningTitle ?: "A game",
                icon = Icons.Rounded.SportsEsports,
                subtitle = "Playing since ${clock(running.startMillis)}",
                trailing = {
                    if (!state.readOnly) {
                        FilledTonalButton(onClick = viewModel::stop, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Text("Stop", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            ) {
                // "Still playing?" (13.1): unanswered, the session ends at the time it was asked.
                if (asked?.askedAt != null && !state.readOnly) {
                    Text(
                        "Still playing? No answer by ${clock(asked.askedAt + GameSessionGuard.ANSWER_MS)}: the session ends at ${clock(asked.nextAskAt)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = viewModel::keepPlaying, shapes = ButtonDefaults.shapes()) { Text("Keep playing") }
                }
            }
        } else if (!state.readOnly && state.pages.isNotEmpty()) {
            Button(onClick = { picking = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text("Start a session", modifier = Modifier.padding(start = 6.dp))
            }
        }

        PsnLineRow(state.psn, onOpenPsn)
        XboxLineRow(state.xbox, onOpenXbox)

        if (!state.loading && state.pages.isEmpty()) {
            EmptyHint("No game pages yet. A note with game_template in its frontmatter shows here.")
        }
        GameGroup.entries.forEach { group ->
            val rows = state.groups[group].orEmpty()
            if (rows.isEmpty()) return@forEach
            Text(group.label, style = MaterialTheme.typography.titleLargeEmphasized)
            // 13.2: the games being played as an expressive carousel; the other groups as cards.
            if (group == GameGroup.PLAYING) {
                PlayingCarousel(rows, onOpen = { onOpenGame(it.page.path) })
            } else {
                // The Android 16 list: one segmented group, each game its own tonal row.
                Column(
                    modifier = Modifier.clip(MaterialTheme.shapes.extraLarge),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    rows.forEach { row -> GameCard(row, onClick = { onOpenGame(row.page.path) }, onOpenMap = { onOpenMap(row.page.path) }) }
                }
            }
        }

        // One fold per console (13.3): a title linked to a deleted page comes back here too.
        GameConsole.entries.forEach { console ->
            val titles = state.unlinked.filter { it.console == console }
            if (titles.isNotEmpty()) {
                key(console) {
                    UnlinkedFold(
                        heading = "On ${console.label}, not in your library",
                        titles = titles,
                        readOnly = state.readOnly,
                        onLink = { linking = it },
                        onHide = viewModel::hide
                    )
                }
            }
        }
    }

    if (picking) {
        GamePickerDialog(
            pages = state.pages,
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
            pages = state.pages,
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

@Composable
private fun GameCard(row: GameRow, onClick: () -> Unit, onOpenMap: () -> Unit) {
    val facts = listOfNotNull(
        row.page.template.label.takeIf { row.page.template.key.isNotEmpty() },
        if (row.page.state == GameState.PAUSED) "Paused" else null,
        row.hours?.let { hours(it) },
        row.trophyPercent?.let { "$it % 🏆" },
        row.achievementPercent?.let { "$it % ${AchievementSection.ICON}" },
        row.lastPlayed?.let { "played ${day(it)}" }
    )
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            supportingContent = { if (facts.isNotEmpty()) Text(facts.joinToString(" · ")) },
            leadingContent = {
                // PSN's icon for the game when a linked title has one (RFC-009 §8), else the pad.
                if (row.iconUrl != null) {
                    AsyncImage(
                        model = row.iconUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    // 13.2: an expressive badge in the game's own tone (the device's colours).
                    ExpressiveBadge(Icons.Rounded.SportsEsports, key = row.page.title, size = 48.dp)
                }
            },
            // 13.2: the game's map, when its page has one.
            trailingContent = if (row.page.maps.isEmpty()) null else {
                { IconButton(onClick = onOpenMap, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Map, contentDescription = "Open the map") } }
            }
        ) {
            Text(
                if (row.page.state == GameState.HUNDRED) "${row.page.title}  ·  100%" else row.page.title,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * The games being played (13.2, Material 3 Expressive): a multi-browse carousel of big tiles, each in
 * the game's own tone of the device's colours, with its expressive shape, title, template and hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayingCarousel(rows: List<GameRow>, onOpen: (GameRow) -> Unit) {
    val state = rememberCarouselState { rows.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 260.dp,
        itemSpacing = 10.dp,
        modifier = Modifier.fillMaxWidth().height(230.dp)
    ) { index ->
        val row = rows[index]
        val tone = toneFor(row.page.title)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .background(tone.container)
                .clickable { onOpen(row) }
                .padding(20.dp)
        ) {
            ExpressiveBadge(
                Icons.Rounded.SportsEsports,
                key = row.page.title,
                size = 76.dp,
                tone = Tone(tone.content.copy(alpha = 0.14f), tone.content),
                modifier = Modifier.align(Alignment.TopStart)
            )
            // The text fades as the item narrows (M3 carousel): nothing is cut mid-word.
            val info = carouselItemDrawInfo
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        val span = (info.maxSize - info.minSize).coerceAtLeast(1f)
                        alpha = ((info.size - info.minSize) / span).coerceIn(0f, 1f)
                    }
            ) {
                Text(
                    row.page.title,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = tone.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val facts = listOfNotNull(
                    row.page.template.label.takeIf { row.page.template.key.isNotEmpty() },
                    row.hours?.let { hours(it) },
                    row.lastPlayed?.let { "played ${day(it)}" }
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = tone.content.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
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

/** One console's titles no page claims, under [heading] (13.3: a fold per console). */
@Composable
private fun UnlinkedFold(
    heading: String,
    titles: List<UnlinkedTitle>,
    readOnly: Boolean,
    onLink: (UnlinkedTitle) -> Unit,
    onHide: (UnlinkedTitle) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$heading (${titles.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (open) "Fold" else "Unfold")
    }
    if (!open) return
    titles.forEach { title ->
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title.name, style = MaterialTheme.typography.titleSmall)
                // The hours only when the console says (an Xbox title may not report its minutes).
                val facts = listOfNotNull(title.hours?.let { hours(it) }, title.lastPlayed?.let { "played ${day(it)}" })
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!readOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onLink(title) }, shapes = ButtonDefaults.shapes()) { Text("Link to a page") }
                        TextButton(onClick = { onHide(title) }, shapes = ButtonDefaults.shapes()) { Text("Hide") }
                    }
                }
            }
        }
    }
}

/**
 * Picks a game page: the one played last first (RFC-009 §4.3), then the ones being played, then the
 * next ones, then by title. 13.1: the list scrolls (41 pages overflowed the dialog), a search field
 * finds a game by its title or an alias, and [offerEarlier] adds "Started 10 / 20 / 30 min ago".
 */
@OptIn(ExperimentalLayoutApi::class)
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
    }
    val needle = GameNames.normalize(query)
    val shown = if (needle.isEmpty()) ordered else ordered.filter { page -> page.names.any { GameNames.normalize(it).contains(needle) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offerEarlier) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EARLIER_CHOICES.forEach { minutes ->
                            FilterChip(
                                selected = minutesAgo == minutes,
                                onClick = { minutesAgo = minutes },
                                label = { Text(if (minutes == 0) "Now" else "$minutes min ago") }
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
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}

/** "Started earlier" (13.1): the minutes a session can be backdated by when it starts. */
private val EARLIER_CHOICES = listOf(0, 10, 20, 30)

/** The search field shows from this many pages on. */
private const val SEARCH_FROM = 8

/** Shown once a game night would start without Do Not Disturb access (D7). */
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

internal fun hours(value: Double): String = when {
    value < 1.0 -> "${(value * 60).toInt()} min"
    value < 10.0 -> String.format(Locale.ENGLISH, "%.1f h", value)
    else -> "${value.toInt()} h"
}

internal fun day(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

internal fun clock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
