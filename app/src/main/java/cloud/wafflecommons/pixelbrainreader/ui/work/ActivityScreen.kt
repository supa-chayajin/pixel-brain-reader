package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.sync.ProjectNews
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncDigest
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncDigestRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** The vault's last 30 days, from git: commits per day, the notes that changed most, the new ones. */
data class VaultActivity(
    val commitsPerDay: List<Pair<LocalDate, Int>>,
    val busiest: List<Pair<String, Int>>,
    val created: List<String>
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val digestRepository: SyncDigestRepository,
    private val jGitProvider: JGitProvider,
    private val gitFileAges: GitFileAges,
    modesRepository: ModesRepository
) : ViewModel() {

    val digest: StateFlow<SyncDigest?> = digestRepository.digest
    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _activity = MutableStateFlow<VaultActivity?>(null)
    val activity: StateFlow<VaultActivity?> = _activity.asStateFlow()

    init {
        viewModelScope.launch { digestRepository.load() }
    }

    fun loadActivity(days: Int = 30) {
        viewModelScope.launch {
            _activity.value = try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now()
                val since = today.minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val commits = jGitProvider.commitsSince(since)
                val perDay = commits.groupingBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }.eachCount()
                val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
                val busiest = commits.filter { it.timeMillis >= weekStart }
                    .flatMap { c -> c.paths.filter { it.endsWith(".md") } }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
                val created = gitFileAges.history().firstSeen
                    .filter { (path, time) -> path.endsWith(".md") && time >= weekStart }
                    .entries.sortedByDescending { it.value }.map { it.key }
                VaultActivity(
                    commitsPerDay = (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to (perDay[it] ?: 0) } },
                    busiest = busiest,
                    created = created
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultActivity(emptyList(), emptyList(), emptyList())
            }
        }
    }

    fun dismiss() {
        viewModelScope.launch { digestRepository.dismiss() }
    }
}

private fun title(path: String) = path.substringAfterLast('/').removeSuffix(".md")

/** Sync digest + vault activity (E5). */
@Composable
fun ActivityScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val digest by viewModel.digest.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadActivity() }

    FormScaffold(title = "Vault activity", onBack = onBack) {
        val d = digest
        SectionCard(
            title = "Last sync",
            icon = Icons.Rounded.CloudDownload,
            subtitle = d?.let {
                val at = Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault())
                "${at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))}: " +
                    "${it.added.size} new, ${it.modified.size} changed, ${it.deleted.size} removed."
            } ?: "Nothing pulled from the remote yet on this phone."
        ) {
            d?.let {
                ForYouLists(it, visibility, onOpenNote)
                DigestLists(it, visibility, onOpenNote)
            }
        }
        val a = activity
        if (a == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@FormScaffold
        }
        SectionCard(title = "Commits, last 30 days", icon = Icons.Rounded.BarChart, subtitle = "${a.commitsPerDay.sumOf { it.second }} commits") {
            CommitBars(a.commitsPerDay)
        }
        SectionCard(title = "Changed most this week", icon = Icons.Rounded.Whatshot) {
            val shown = a.busiest.filter { visibility.isVisible(it.first) }
            if (shown.isEmpty()) EmptyHint("No note changed this week.")
            shown.forEach { (path, count) ->
                Text("${title(path)} · $count", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenNote(path) }.padding(vertical = 4.dp))
            }
        }
        SectionCard(title = "New this week", icon = Icons.Rounded.NewReleases) {
            val shown = a.created.filter { visibility.isVisible(it) }
            if (shown.isEmpty()) EmptyHint("No new note this week.")
            shown.take(20).forEach { path ->
                Text(title(path), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenNote(path) }.padding(vertical = 4.dp))
            }
        }
    }
}

/** C4: what the pull brought that concerns you, per project — before the full list of changes. */
@Composable
private fun ForYouLists(digest: SyncDigest, visibility: Visibility, onOpenNote: (String) -> Unit) {
    val news = digest.forYou.filter { visibility.isVisible(it.root) }
    if (news.isEmpty()) return
    Text("For you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    news.forEach { n ->
        Text(n.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        n.myLines.forEach { Text("☐ $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenNote("${n.root}/TODO.md") }.padding(vertical = 3.dp)) }
        n.reports.forEach { path ->
            Text("📄 ${title(path)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenNote(path) }.padding(vertical = 3.dp))
        }
        n.pickedUp.forEach { Text("✓ Picked up: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

/** "2 lines for you · 1 report · 1 hand-off picked up". */
internal fun ProjectNews.summary(): String = listOfNotNull(
    myLines.size.takeIf { it > 0 }?.let { "$it line${if (it == 1) "" else "s"} for you" },
    reports.size.takeIf { it > 0 }?.let { "$it report${if (it == 1) "" else "s"}" },
    pickedUp.size.takeIf { it > 0 }?.let { "$it hand-off${if (it == 1) "" else "s"} picked up" }
).joinToString(" · ")

@Composable
private fun DigestLists(digest: SyncDigest, visibility: Visibility, onOpenNote: (String) -> Unit) {
    listOf("New" to digest.added, "Changed" to digest.modified).forEach { (label, paths) ->
        val shown = paths.filter { visibility.isVisible(it) }
        if (shown.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            shown.take(15).forEach { path ->
                Text(title(path), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenNote(path) }.padding(vertical = 3.dp))
            }
            if (shown.size > 15) Text("and ${shown.size - 15} more", style = MaterialTheme.typography.bodySmall)
        }
    }
    val removed = digest.deleted.filter { visibility.isVisible(it) }
    if (removed.isNotEmpty()) Text("Removed: " + removed.joinToString(", ") { title(it) }, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CommitBars(days: List<Pair<LocalDate, Int>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        days.forEach { (_, count) ->
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((count.toFloat() / max).coerceAtLeast(if (count > 0) 0.04f else 0.01f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

/** The Daily screen's "since your last sync" card, until dismissed. */
@Composable
fun SyncDigestCard(onOpen: () -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val digest by viewModel.digest.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val d = digest?.takeIf { !it.dismissed && it.total > 0 } ?: return
    val news = d.forYou.filter { visibility.isVisible(it.root) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("From your last sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "${d.added.size} new, ${d.modified.size} changed, ${d.deleted.size} removed — mostly in " +
                    d.folders().take(2).joinToString(" and ") { it.first },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            news.take(3).forEach { n ->
                Text(
                    "For you in ${n.name}: ${n.summary()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Row {
                TextButton(onClick = onOpen) { Text("See what changed") }
                TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }
            }
        }
    }
}
