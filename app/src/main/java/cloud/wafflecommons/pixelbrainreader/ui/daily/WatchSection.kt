package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.watch.DailyFeeds
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfigRepository
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedEntry
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImportRepository
import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker
import cloud.wafflecommons.pixelbrainreader.ui.components.DailyBlockHeader
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

/**
 * Daily's Feeds block (D5; 13.4, F5: the five newest unread of the mode's feeds). Read when the
 * mode changes, when the feeds page saves, each time the app comes back to the foreground and
 * when a sync ends (12.18, D5a: it may bring a changed feeds.json); each of these refreshes the
 * feeds that are due through their 2-hour cache, never on a timer, a feed with nothing cached yet
 * read at once. The headlines then follow the cache and this device's read marks by themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val repository: WatchRepository,
    private val shareImport: ShareImportRepository,
    @param:ApplicationContext private val context: Context,
    modesRepository: ModesRepository,
    feedConfig: FeedConfigRepository,
    appClock: AppClock,
    syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    private val reads = MutableStateFlow(0)

    /** The day the block is for (today's page only) and its headlines. */
    val watch: StateFlow<Pair<LocalDate, DailyFeeds>?> =
        combine(appClock.today, modesRepository.visibility, feedConfig.saved, reads) { today, visibility, _, _ -> today to visibility }
            .flatMapLatest { (today, visibility) ->
                refreshIfDue()
                repository.daily(visibility)
                    .map { today to it }
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "The feeds couldn't be read: ${e.javaClass.simpleName}")
                        emit(today to DailyFeeds())
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { appClock.foregroundEvents.collect { reads.update { it + 1 } } }
        viewModelScope.launch {
            syncOrchestrator.syncState
                .map { it is SyncState.Success }
                .distinctUntilChanged()
                .drop(1) // the state the block found: only a sync that ends from now on
                .filter { it }
                .collect { reads.update { it + 1 } }
        }
    }

    /**
     * Marks [entry] read and opens it in the share screen's reader (its Import is there too; "Add as
     * a game map" is not, being for a link shared from a browser, 13.3); the caller shows that screen.
     */
    fun read(entry: FeedEntry) {
        markRead(entry.url)
        shareImport.start(SharedContent(url = entry.url, titleHint = entry.title, passage = null), html = null, offerAsMap = false)
    }

    /** A read-only device: marks [entry] read; the caller opens the browser. */
    fun openedOutside(entry: FeedEntry) {
        markRead(entry.url)
    }

    /** Marks [entry] read and imports it straight to the inbox, in the background. False on a read-only device. */
    fun import(entry: FeedEntry): Boolean {
        if (VaultWriteGate.refuses()) return false
        markRead(entry.url)
        ImportWorker.enqueue(context, entry.url)
        return true
    }

    private fun refreshIfDue() {
        viewModelScope.launch {
            try {
                repository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The feeds couldn't be refreshed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun markRead(url: String) {
        viewModelScope.launch {
            try {
                repository.markRead(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A headline couldn't be marked read: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "WatchBlock"
    }
}

/**
 * Daily's Feeds block (F5): the five newest unread headlines of the mode's feeds, one segmented
 * group, then See all (the Feeds tab). Text only: the pictures are the tab's. A tap reads the
 * article in the app's reader; the inbox button imports it without leaving Daily. [onImport] is
 * null on a read-only device, where [onRead] opens the article outside the app instead. All read:
 * one line says so, then See all.
 */
@Composable
fun WatchSection(
    daily: DailyFeeds,
    now: Long,
    onRead: (FeedEntry) -> Unit,
    onImport: ((FeedEntry) -> Unit)?,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DailyBlockHeader(icon = Icons.Rounded.RssFeed, title = "Feeds", modifier = Modifier.padding(horizontal = 4.dp))
        // Each row in its own segment: the group itself never clips (a row keeps its corners and margins).
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (daily.entries.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = segmentShape(0, 1),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Nothing new since you last looked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            } else {
                daily.entries.forEachIndexed { index, entry ->
                    key(entry.url) {
                        HeadlineRow(
                            entry = entry,
                            shape = segmentShape(index, daily.entries.size),
                            age = ageOf(entry.publishedAt, now),
                            onRead = onRead,
                            onImport = onImport
                        )
                    }
                }
            }
        }
        TextButton(onClick = onSeeAll, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
            Text("See all")
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HeadlineRow(
    entry: FeedEntry,
    shape: Shape,
    age: String,
    onRead: (FeedEntry) -> Unit,
    onImport: ((FeedEntry) -> Unit)?
) {
    Surface(
        onClick = { onRead(entry) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = if (onImport != null) 4.dp else 20.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.feedLabel} · $age",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onImport != null) {
                IconButton(onClick = { onImport(entry) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Inbox, contentDescription = "Import to the inbox", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** "Just now", "40 min ago", "5 h ago", "3 days ago". */
internal fun ageOf(publishedAt: Long, now: Long): String {
    val age = Duration.ofMillis((now - publishedAt).coerceAtLeast(0))
    return when {
        age.toMinutes() < 1 -> "Just now"
        age.toHours() < 1 -> "${age.toMinutes()} min ago"
        age.toDays() < 1 -> "${age.toHours()} h ago"
        age.toDays() == 1L -> "Yesterday"
        else -> "${age.toDays()} days ago"
    }
}
