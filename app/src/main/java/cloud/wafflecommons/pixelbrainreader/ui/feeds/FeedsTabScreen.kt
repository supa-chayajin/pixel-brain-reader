@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.feeds

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedEntry
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedStream
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.daily.ageOf
import cloud.wafflecommons.pixelbrainreader.ui.main.ExpressiveNavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.ContentMaxWidth

/**
 * The Feeds tab (13.4, F1–F3, F7): the mode's feeds mixed in one stream, newest first, by day; the
 * chips filter by feed and carry their unread counts; a tap opens the reader (the browser on a
 * read-only device), the inbox button imports; read headlines are dimmed, Mark all read clears the
 * view, pull to refresh reads every feed again. A tab: no back arrow, and its header's title is
 * the tab's name, so the open tab's drawn emblem shows there. One centred column on a wide window.
 */
@Composable
fun FeedsTabScreen(
    onOpenReader: () -> Unit,
    onOpenFeedsSettings: () -> Unit,
    viewModel: FeedsTabViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The tab's dot clears for what is here when it opens, and for what came meanwhile when it is left.
    LaunchedEffect(Unit) { viewModel.tabSeen() }
    DisposableEffect(viewModel) { onDispose { viewModel.tabSeen() } }

    // A read-only device imports nothing (settled decision #18): the inbox button goes, a tap opens the browser.
    val readOnly = VaultWriteGate.readOnly
    val onOpen: (FeedEntry) -> Unit = { entry ->
        if (readOnly) {
            // A read-only device imports nothing: the article opens outside the app.
            viewModel.openedOutside(entry)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, entry.url.toUri())) }
        } else {
            viewModel.open(entry)
            onOpenReader()
        }
    }
    val onImport: ((FeedEntry) -> Unit)? = if (readOnly) {
        null
    } else {
        { entry ->
            if (viewModel.import(entry)) Toast.makeText(context, "Importing to the inbox…", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = "Feeds",
                icon = Icons.Rounded.RssFeed,
                actions = {
                    IconButton(
                        onClick = viewModel::markAllRead,
                        enabled = state.unreadInView > 0,
                        shapes = IconButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = "Mark all read")
                    }
                    IconButton(onClick = onOpenFeedsSettings, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Manage feeds")
                    }
                }
            )
        }
    ) { padding ->
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refreshNow,
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pull,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FeedsContent(
                state = state,
                onSelect = viewModel::select,
                onOpen = onOpen,
                onImport = onImport,
                onManage = onOpenFeedsSettings
            )
        }
    }
}

/**
 * The stream: the chips, then each day's headlines as one segmented group; or what is missing. The
 * list takes the whole width and centres its column through its padding, so a drag in the margins
 * of a wide window still scrolls, pulls to refresh and hides the floating bar (as the Games grid).
 */
@Composable
private fun FeedsContent(
    state: FeedsTabState,
    onSelect: (String?) -> Unit,
    onOpen: (FeedEntry) -> Unit,
    onImport: ((FeedEntry) -> Unit)?,
    onManage: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = max(16.dp, (maxWidth - ContentMaxWidth) / 2 + 16.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = ExpressiveNavBarClearance),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            when {
                state.loading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                state.stream.chips.isEmpty() -> item(key = "no-feed") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                        EmptyHint("No feed shows in ${state.appearance.label(state.mode)} mode.")
                        TextButton(onClick = onManage, shapes = ButtonDefaults.shapes()) { Text("Manage feeds") }
                    }
                }
                else -> {
                    item(key = "chips") { FeedChips(stream = state.stream, selected = state.selected, onSelect = onSelect) }
                    if (state.days.isEmpty()) {
                        item(key = "empty") {
                            EmptyHint(
                                text = if (state.selected != null) {
                                    "Nothing from this feed in the last 30 days."
                                } else {
                                    "Nothing yet: pull to refresh."
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    state.days.forEach { day ->
                        item(key = "day:${day.date}") {
                            DayHeader(label = day.label, modifier = Modifier.animateItem())
                        }
                        itemsIndexed(day.entries, key = { _, entry -> "entry:${entry.url}" }) { index, entry ->
                            HeadlineRow(
                                entry = entry,
                                shape = segmentShape(index, day.entries.size),
                                now = state.now,
                                onOpen = onOpen,
                                onImport = onImport,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "All", then each feed of the mode in the owner's order, each with its unread count. */
@Composable
private fun FeedChips(stream: FeedStream, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "all") {
            FeedFilterChip(label = "All", unread = stream.unread, selected = selected == null, onClick = { onSelect(null) })
        }
        items(stream.chips, key = { "feed:${it.feed.url}" }) { chip ->
            FeedFilterChip(
                label = chip.feed.label,
                unread = chip.unread,
                selected = selected == chip.feed.url,
                onClick = { onSelect(chip.feed.url) }
            )
        }
    }
}

@Composable
private fun FeedFilterChip(label: String, unread: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The count is said with the name; the badge's digits are not read again.
                modifier = Modifier.semantics { contentDescription = if (unread > 0) "$label, $unread unread" else label }
            )
        },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        trailingIcon = if (unread > 0) {
            {
                Badge(modifier = Modifier.clearAndSetSemantics {}) {
                    Text(if (unread > MAX_BADGE) "$MAX_BADGE+" else unread.toString())
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 8.dp, top = 16.dp, bottom = 6.dp)
            .semantics { heading() }
    )
}

/**
 * A headline: its picture when the feed gives one, the title (two lines), "feed · age", the inbox
 * button on a writing device. A read one is dimmed: its text in onSurfaceVariant, its title not
 * bold, its picture at 60 %.
 */
@Composable
private fun HeadlineRow(
    entry: FeedEntry,
    shape: Shape,
    now: Long,
    onOpen: (FeedEntry) -> Unit,
    onImport: ((FeedEntry) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val read = entry.read
    Surface(
        onClick = { onOpen(entry) },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (read) "Read" else "Unread" }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = if (onImport != null) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The margin goes with the picture: a picture that can't load leaves no gap.
            FeedThumbnail(
                url = entry.thumbnailUrl,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .alpha(if (read) READ_PICTURE_ALPHA else 1f)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.feedLabel} · ${ageOf(entry.publishedAt, now)}",
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

/** Past this, a chip's badge reads "99+". */
private const val MAX_BADGE = 99

/** A read headline's picture, dimmed with its text (F3). */
private const val READ_PICTURE_ALPHA = 0.6f
