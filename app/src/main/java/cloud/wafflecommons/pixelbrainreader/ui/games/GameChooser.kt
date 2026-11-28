package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameState

/**
 * Picks a game page (13.4): each row with its 40 dp picture; the played-last first; search past 8 pages;
 * "Started earlier" chips when [offerEarlier]. The session picker, "Link to a page" and a shared
 * image's "Picture for" all use it. The order is RFC-009 §4.3's: the one played last, then the ones
 * being played, then the next ones, then by title; the search finds a game by its title or an alias.
 * [arts] holds each page's picture by path; a page without one shows its initials.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameChooserDialog(
    pages: List<GamePage>,
    arts: Map<String, GameArt>,
    onPick: (page: GamePage, minutesAgo: Int) -> Unit,
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
                        CHOOSER_EARLIER_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = minutesAgo == minutes,
                                onClick = { minutesAgo = minutes },
                                label = { Text(if (minutes == 0) "Now" else "$minutes min ago") }
                            )
                        }
                    }
                }
                if (pages.size > CHOOSER_SEARCH_FROM) {
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
                            onClick = { onPick(page, minutesAgo) },
                            leadingContent = {
                                GamePicture(
                                    art = arts[page.path] ?: GameArtRules.initialsOnly(page.title),
                                    modifier = Modifier.size(GamePictureSizes.Row),
                                    contentDescription = null
                                )
                            },
                            supportingContent = {
                                Text(if (page.path == lastPlayed) "Played last · ${page.state.label}" else page.state.label)
                            }
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
private val CHOOSER_EARLIER_MINUTES = listOf(0, 10, 20, 30)

/** The search field shows from this many pages on. */
private const val CHOOSER_SEARCH_FROM = 8
