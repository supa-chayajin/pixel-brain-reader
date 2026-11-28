package cloud.wafflecommons.pixelbrainreader.data.watch

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.web.WebPageLoader
import com.prof18.rssparser.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The feeds (D5; the Feeds tab and Daily's block from 13.4): the feeds of `feeds.json`, read when
 * the app comes back to the foreground, when the mode changes and when a sync ends, through the Room
 * cache (fresh for [TechWatch.FRESH_FOR_MS]), never on a timer and never by a worker. A feed is
 * fetched like a shared page, through [WebPageLoader.download]: http(s) only, public addresses
 * only, every redirect checked; the XML is then parsed from that text (the parser's own client
 * would skip the guard). The cache keeps what each feed carried in the last 30 days (F6), and past
 * them what it still carries, out of the view; each headline with the time it was first seen; the
 * read marks are this device's own ([FeedMarksStore], F3). A feed that fails keeps its headlines,
 * and its failure is kept for the feeds page (12.18, D5a).
 */
@Singleton
class WatchRepository @Inject constructor(
    private val feeds: FeedConfigRepository,
    private val newsDao: NewsDao,
    private val database: AppDatabase,
    private val loader: WebPageLoader,
    private val preferences: UserPreferencesRepository,
    private val marks: FeedMarksStore
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var lastFailure: Long? = null

    /** The feeds the last refresh read from `feeds.json` (13.4): a sync can change the file without a save here. */
    private var lastList: List<Feed>? = null

    /** Moves when a refresh reads a list other than the last one: the tab's dot reads the feeds again. */
    private val listVersion = MutableStateFlow(0)

    /** Reads the feeds of the list that are due (every one when [force]d); the screens follow [stream]. */
    suspend fun refresh(force: Boolean = false) {
        withContext(Dispatchers.IO) { refreshIfDue(feeds.read().feeds, force) }
    }

    /** The Feeds tab (F2): the feeds [visibility] shows and their headlines, newest first, each with its mark. */
    fun stream(visibility: Visibility): Flow<FeedStream> = flow {
        val shown = feeds.read().visible(visibility)
        if (shown.isEmpty()) {
            emit(FeedStream())
        } else {
            emitAll(
                combine(newsDao.bySourcesFlow(shown.map { it.url }), marks.marks) { rows, marked ->
                    TechWatch.stream(shown, rows.map(::cached), marked, oldest = TechWatch.cutoff(System.currentTimeMillis()))
                }
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Daily's block (F5): the newest unread headlines of the feeds [visibility] shows. */
    fun daily(visibility: Visibility): Flow<DailyFeeds> =
        stream(visibility).map { TechWatch.daily(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * The tab's dot: a headline of the feeds the mode shows, first seen since the tab was last
     * opened, still unread. Follows the mode, the feeds page's saves, the list a refresh last read
     * (a `feeds.json` a sync changed), the cache and the marks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun newSinceOpened(visibility: Flow<Visibility>): Flow<Boolean> =
        combine(visibility, feeds.saved, listVersion) { shownBy, _, _ -> shownBy }
            .mapLatest { shownBy -> feeds.read().visible(shownBy).map { it.url } }
            .distinctUntilChanged()
            .flatMapLatest { urls ->
                if (urls.isEmpty()) {
                    flowOf(false)
                } else {
                    marks.marks.map { it.tabOpenedAt }.distinctUntilChanged().flatMapLatest { after ->
                        combine(newsDao.seenAfterFlow(urls, after), marks.marks) { rows, marked ->
                            TechWatch.hasNew(rows.map(::cached), marked, oldest = TechWatch.cutoff(System.currentTimeMillis()))
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** [url] opened (in the reader or the browser) or imported: read on this device. */
    suspend fun markRead(url: String) {
        withContext(Dispatchers.IO) { marks.update { FeedMarkRules.markRead(it, url) } }
    }

    /** "Mark all read" on [entries] (the view): each feed's mark moves to its newest headline there. */
    suspend fun markAllRead(entries: List<FeedEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) { marks.update { FeedMarkRules.markAllRead(it, entries) } }
    }

    /** The Feeds tab opened or left at [now]: its dot clears for what arrived until then. */
    suspend fun tabOpened(now: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) { marks.update { FeedMarkRules.opened(it, now) } }
    }

    /** Each feed's last read and last failure (the feeds page). */
    suspend fun statuses(): Map<String, FeedStatus> = withContext(Dispatchers.IO) {
        FeedStatuses.decode(preferences.watchFeedStatus.first())
    }

    /** The feeds page's "Check now": every feed read again, its status recorded. */
    suspend fun checkNow(): Map<String, FeedStatus> = withContext(Dispatchers.IO) {
        refreshIfDue(feeds.read().feeds, force = true)
        statuses()
    }

    /** [all], just read from `feeds.json`, against the last refresh's list: a change moves [listVersion]. */
    @Synchronized
    private fun followList(all: List<Feed>) {
        if (all == lastList) return
        lastList = all
        listVersion.update { it + 1 }
    }

    private suspend fun refreshIfDue(all: List<Feed>, force: Boolean) {
        followList(all)
        // No feed listed (none yet, the vault being cloned, an unreadable file): nothing to read,
        // and the cache, the statuses and the marks wait for the list to come back.
        if (all.isEmpty()) return
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val stored = FeedStatuses.decode(preferences.watchFeedStatus.first())
            var statuses = stored
            // 13.4: the cache keeps 30 days and a row keeps its first-seen time, so the freshness is
            // the newest read, and a feed read once counts as known even when all it carries is old.
            val lastRead = stored.values.mapNotNull { it.readAt }.maxOrNull()
            val known = newsDao.sources().toSet() + stored.filterValues { it.readAt != null }.keys
            val toRead = TechWatch.feedsToRead(
                all, known, lastRead, lastFailure, now, force,
                failedRecently = FeedStatuses.failedRecently(statuses, now)
            )
            if (toRead.isNotEmpty()) {
                val read = coroutineScope { toRead.map { feed -> async { feed.url to fetch(feed, now) } }.awaitAll() }
                read.forEach { (url, problem) ->
                    statuses = if (problem == null) FeedStatuses.read(statuses, url, now) else FeedStatuses.failed(statuses, url, now, problem)
                }
                // A whole refresh that read nothing (offline) waits before the next one.
                if (toRead.size == all.size) lastFailure = if (read.all { it.second != null }) now else null
            }
            // History (F6) is each feed's own, at its reads (keep): a headline its feed still carries
            // stays past the 30 days, out of the view. A feed taken out of the list takes its
            // headlines, its status and its marks with it.
            val urls = all.map { it.url }.toSet()
            newsDao.deleteOtherSources(urls.toList())
            val kept = statuses.filterKeys { it in urls }
            if (kept != stored) preferences.setWatchFeedStatus(FeedStatuses.encode(kept))
            val cachedUrls = newsDao.urls().toSet()
            marks.update { FeedMarkRules.prune(it, cachedUrls, urls) }
        }
    }

    /** Null when the feed was read (its headlines merged into the cache), else why it wasn't. */
    private suspend fun fetch(feed: Feed, now: Long): String? = try {
        val xml = loader.download(feed.url, NetPurpose.WATCH).html
        val channel = RssParser().parse(xml)
        val items = channel.items.map { FeedItem(it.title, it.link, it.pubDate, listOfNotNull(it.image, it.itunesItemData?.image)) }
        if (!TechWatch.hasHeadlines(items)) {
            FeedStatuses.problemOf(null)
        } else {
            // Read: a feed whose items are all older than 30 days answered too. Its rows are stamped
            // with the time they enter the cache, once downloaded (13.4).
            keep(feed, items, now, seenAt = System.currentTimeMillis())
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A feed couldn't be read: ${e.javaClass.simpleName}")
        FeedStatuses.problemOf(e)
    }

    /**
     * A successful read of [feed] into the cache, in one transaction (13.4, F6): its headlines merged
     * (a known one keeps its first-seen time, its feed and, undated, its date), then its rows the read
     * no longer carries that are past the 30 days of [now] go, then its newest
     * [TechWatch.MAX_PER_FEED] stay. A row the read still carries is never deleted for its age, so its
     * read mark stays and it never comes back as new. [seenAt]: when new rows entered the cache.
     */
    internal suspend fun keep(feed: Feed, items: List<FeedItem>, now: Long, seenAt: Long) {
        val fresh = TechWatch.articlesOf(feed, items, now, seenAt)
        database.withTransaction {
            if (fresh.isNotEmpty()) {
                val known = newsDao.byUrls(fresh.map { it.url }).map(::cached)
                newsDao.insertAll(TechWatch.merge(fresh, known).map(::entity))
            }
            newsDao.deleteOlderThanExcept(feed.url, TechWatch.cutoff(now), fresh.map { it.url })
            newsDao.trimSource(feed.url, TechWatch.MAX_PER_FEED)
        }
    }

    private companion object {
        const val TAG = "TechWatch"
    }
}

/** A cache row as the feeds' rules read it. */
private fun cached(row: NewsArticleEntity): CachedArticle =
    CachedArticle(row.url, row.title, row.sourceName, row.publishedDate, row.fetchDate, row.thumbnailUrl)

/** A row for the cache: `fetchDate` is its first-seen time (13.4). */
private fun entity(article: CachedArticle): NewsArticleEntity =
    NewsArticleEntity(article.url, article.title, article.source, article.thumbnailUrl, article.publishedAt, article.fetchedAt)
