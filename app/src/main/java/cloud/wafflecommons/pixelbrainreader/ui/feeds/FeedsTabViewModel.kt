package cloud.wafflecommons.pixelbrainreader.ui.feeds

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfigRepository
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedEntry
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedStream
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImportRepository
import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What the Feeds tab shows (13.4, F2–F3). */
data class FeedsTabState(
    /** Nothing read yet: the first emission of the stream is on its way. */
    val loading: Boolean = true,
    val stream: FeedStream = FeedStream(),
    /** A feed's address; null = every feed of the mode (the "All" chip). */
    val selected: String? = null,
    /** The view ([selected] applied), grouped by day, built off the main thread. */
    val days: List<FeedDay> = emptyList(),
    val unreadInView: Int = 0,
    val refreshing: Boolean = false,
    val appearance: ModeAppearance = ModeAppearance(),
    val mode: ContextMode = ContextMode.WORK,
    /** When the state was built: the headlines' ages read from it. */
    val now: Long = 0L
)

/**
 * The Feeds tab (13.4, F1–F3). The stream restarts on a mode change, a save of the feeds page, the
 * app's return to the foreground and a sync that ends (it may bring a changed feeds.json), as
 * Daily's block does; each of these also refreshes the feeds that are due (never a worker, never a
 * timer). The cache's rows then arrive by themselves (Room). The unread marks are this device's
 * own: a read-only device keeps its marks and imports nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedsTabViewModel @Inject constructor(
    private val repository: WatchRepository,
    private val shareImport: ShareImportRepository,
    @param:ApplicationContext private val context: Context,
    modesRepository: ModesRepository,
    feedConfig: FeedConfigRepository,
    appClock: AppClock,
    syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    private val selected = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)

    /** Bumped when the app comes back and when a sync ends: the stream (and the list) is read again. */
    private val reads = MutableStateFlow(0)

    private val streams: Flow<Pair<Visibility, FeedStream>> =
        combine(modesRepository.visibility, feedConfig.saved, reads) { visibility, _, _ -> visibility }
            .flatMapLatest { visibility ->
                refreshIfDue()
                repository.stream(visibility)
                    .map { visibility to it }
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "The feeds couldn't be read: ${e.javaClass.simpleName}")
                        emit(visibility to FeedStream())
                    }
            }

    val state: StateFlow<FeedsTabState> =
        combine(streams, selected, refreshing, appClock.today) { (visibility, stream), selectedUrl, isRefreshing, today ->
            feedsTabState(
                visibility = visibility,
                stream = stream,
                selectedUrl = selectedUrl,
                refreshing = isRefreshing,
                today = today,
                zone = ZoneId.systemDefault(),
                now = System.currentTimeMillis()
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedsTabState())

    init {
        viewModelScope.launch { appClock.foregroundEvents.collect { reads.update { it + 1 } } }
        viewModelScope.launch {
            syncOrchestrator.syncState
                .map { it is SyncState.Success }
                .distinctUntilChanged()
                .drop(1) // the state the tab found: only a sync that ends from now on
                .filter { it }
                .collect { reads.update { it + 1 } }
        }
    }

    /** A feed's chip ([feedUrl]), or "All" (null). */
    fun select(feedUrl: String?) {
        selected.value = feedUrl
    }

    /** Pull to refresh: what the feeds page's Check now does (every feed read again). */
    fun refreshNow() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                repository.checkNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The feeds couldn't be read again: ${e.javaClass.simpleName}")
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * Marks [entry] read and opens it in the share screen's reader (its Import is there too; "Add as
     * a game map" is not, being for a link shared from a browser); the caller shows that screen.
     */
    fun open(entry: FeedEntry) {
        markRead(entry.url)
        shareImport.start(SharedContent(url = entry.url, titleHint = entry.title, passage = null), html = null, offerAsMap = false)
    }

    /** A read-only device: marks [entry] read; the caller opens the browser. */
    fun openedOutside(entry: FeedEntry) {
        markRead(entry.url)
    }

    /** Marks [entry] read and imports it to the inbox in the background. False on a read-only device. */
    fun import(entry: FeedEntry): Boolean {
        if (VaultWriteGate.refuses()) return false
        markRead(entry.url)
        ImportWorker.enqueue(context, entry.url)
        return true
    }

    /** Every headline of the view read: each feed's mark moves to its newest headline there. */
    fun markAllRead() {
        val view = state.value.days.flatMap { it.entries }
        if (view.none { !it.read }) return
        viewModelScope.launch { quietly("The headlines couldn't be marked read") { repository.markAllRead(view) } }
    }

    /**
     * The tab opened or left: its dot clears for what arrived until now. Written even while the
     * screen goes (the tab left by Back clears this ViewModel): a short DataStore edit.
     */
    fun tabSeen() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                quietly("The tab's opening couldn't be kept") { repository.tabOpened() }
            }
        }
    }

    private fun refreshIfDue() {
        viewModelScope.launch { quietly("The feeds couldn't be refreshed") { repository.refresh() } }
    }

    private fun markRead(url: String) {
        viewModelScope.launch { quietly("A headline couldn't be marked read") { repository.markRead(url) } }
    }

    /** Runs [block]; a failure is logged by its type only (a message can quote an address). */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "FeedsTab"
    }
}

/**
 * The tab's state from one emission (pure): the chip [selectedUrl] (dropped when its feed left the
 * mode), the view grouped by day in [zone], and its unread count.
 */
internal fun feedsTabState(
    visibility: Visibility,
    stream: FeedStream,
    selectedUrl: String?,
    refreshing: Boolean,
    today: LocalDate,
    zone: ZoneId,
    now: Long
): FeedsTabState {
    val selected = selectedUrl?.takeIf { url -> stream.chips.any { it.feed.url == url } }
    val view = if (selected == null) stream.entries else stream.entries.filter { it.feedUrl == selected }
    return FeedsTabState(
        loading = false,
        stream = stream,
        selected = selected,
        days = FeedDays.group(view, today, zone),
        unreadInView = view.count { !it.read },
        refreshing = refreshing,
        appearance = visibility.appearance,
        mode = visibility.mode,
        now = now
    )
}
