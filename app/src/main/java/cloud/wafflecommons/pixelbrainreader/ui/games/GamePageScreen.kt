package cloud.wafflecommons.pixelbrainreader.ui.games

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimeline
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySection
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.main.MarkwonContent
import coil.compose.AsyncImage

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

    FormScaffold(title = page?.title ?: "Game", onBack = onBack) {
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
        }

        if (!state.readOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.running?.page == page.path) {
                    FilledTonalButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Text("Stop", modifier = Modifier.padding(start = 6.dp))
                    }
                } else {
                    Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text("Start a session", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                OutlinedButton(onClick = { onAskClaude(state.askProject, GamePages.askText(page)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.QuestionAnswer, contentDescription = null)
                    Text("Ask Claude", modifier = Modifier.padding(start = 6.dp))
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
                trailing = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Reveal") } }
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
                    val hidden = item.waitsForTrophy && !item.done && !shown(key)
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

        SectionCard(title = "Sessions", icon = Icons.Rounded.History) {
            if (state.days.isEmpty() && state.history.isEmpty()) {
                EmptyHint("No session yet. PlayStation's play time arrives each night; a session started here counts too.")
            }
            state.days.take(30).forEach { (date, line) ->
                Text("${day(date)} ${line.start} · ${GameTimeline.duration(line.minutes)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.history.isNotEmpty()) {
                Text("Earlier, from your Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                state.history.take(30).forEach { entry ->
                    Text("${day(entry.date)} ${entry.time} · ${entry.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard(title = "Notes", icon = Icons.AutoMirrored.Rounded.Article) {
            TextButton(onClick = { onOpenNote(page.path) }) { Text("Open this game's page") }
            state.folderNotes.forEach { note ->
                TextButton(onClick = { onOpenNote(note) }) { Text(note.substringAfterLast('/').removeSuffix(".md")) }
            }
        }
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
