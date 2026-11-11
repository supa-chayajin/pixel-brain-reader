package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedHeadlines
import cloud.wafflecommons.pixelbrainreader.data.watch.Headline
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImportRepository
import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

/**
 * The "Tech watch" block (D5): read when the mode changes and each time the app comes back to the
 * foreground; the feeds refresh through their 2-hour cache, never on a timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val repository: WatchRepository,
    private val shareImport: ShareImportRepository,
    @param:ApplicationContext private val context: Context,
    modesRepository: ModesRepository,
    appClock: AppClock
) : ViewModel() {

    private val reads = MutableStateFlow(0)

    /** The day the headlines are for (the block shows on today's page only), and the headlines per feed. */
    val watch: StateFlow<Pair<LocalDate, List<FeedHeadlines>>?> =
        combine(appClock.today, modesRepository.visibility, reads) { today, visibility, _ -> today to visibility }
            .mapLatest { (today, visibility) ->
                try {
                    today to repository.headlines(visibility)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    today to emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { appClock.foregroundEvents.collect { reads.update { it + 1 } } }
    }

    /** Opens [headline] in the share screen's reader (its Import is there too); the caller shows that screen. */
    fun read(headline: Headline) {
        shareImport.start(SharedContent(url = headline.url, titleHint = headline.title, passage = null), html = null)
    }

    /** Imports [headline] straight to the inbox, in the background. False on a read-only device. */
    fun import(headline: Headline): Boolean {
        if (VaultWriteGate.refuses()) return false
        ImportWorker.enqueue(context, headline.url)
        return true
    }
}

/**
 * Headlines grouped by feed, newest first (D5). A tap reads the article in the app's reader;
 * the inbox button imports it without leaving Daily. [onImport] is null on a read-only device,
 * where [onRead] opens the article outside the app instead.
 */
@Composable
fun WatchSection(
    feeds: List<FeedHeadlines>,
    now: Long,
    onRead: (Headline) -> Unit,
    onImport: ((Headline) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "📰 Tech watch",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        feeds.forEach { group ->
            Text(
                text = group.feed.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            group.headlines.forEach { headline ->
                HeadlineRow(headline = headline, age = ageOf(headline.publishedAt, now), onRead = onRead, onImport = onImport)
            }
        }
    }
}

@Composable
private fun HeadlineRow(headline: Headline, age: String, onRead: (Headline) -> Unit, onImport: ((Headline) -> Unit)?) {
    Surface(
        onClick = { onRead(headline) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = headline.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onImport != null) {
                IconButton(onClick = { onImport(headline) }) {
                    Icon(Icons.Rounded.Inbox, contentDescription = "Import to the inbox", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Spacer(Modifier.width(12.dp))
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
