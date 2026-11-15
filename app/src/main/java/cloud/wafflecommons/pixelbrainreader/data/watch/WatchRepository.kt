package cloud.wafflecommons.pixelbrainreader.data.watch

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.web.WebPageLoader
import com.prof18.rssparser.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tech watch (D5): the feeds of `feeds.json`, read when the app comes back to the foreground
 * through the Room cache (fresh for [TechWatch.FRESH_FOR_MS]), never on a timer and never by a
 * worker. A feed is fetched like a shared page, through [WebPageLoader.download]: http(s) only,
 * public addresses only, every redirect checked; the XML is then parsed from that text (the
 * parser's own client would skip the guard). A feed that fails keeps its last headlines, and its
 * failure is kept for the feeds page (12.18, D5a).
 */
@Singleton
class WatchRepository @Inject constructor(
    private val feeds: FeedConfigRepository,
    private val newsDao: NewsDao,
    private val database: AppDatabase,
    private val loader: WebPageLoader,
    private val preferences: UserPreferencesRepository
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var lastFailure: Long? = null

    /** The headlines of the feeds [visibility] shows, the cache refreshed first when due (or [force]d). */
    suspend fun headlines(visibility: Visibility, force: Boolean = false): List<FeedHeadlines> = withContext(Dispatchers.IO) {
        val config = feeds.read()
        val shown = config.visible(workHidden = visibility.workContextHidden)
        if (shown.isEmpty()) return@withContext emptyList()
        refreshIfDue(config.feeds, force)
        val cached = newsDao.bySources(shown.map { it.url }).map {
            CachedArticle(it.url, it.title, it.sourceName, it.publishedDate, it.fetchDate)
        }
        TechWatch.group(shown, cached)
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

    private suspend fun refreshIfDue(all: List<Feed>, force: Boolean) = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        val stored = FeedStatuses.decode(preferences.watchFeedStatus.first())
        var statuses = stored
        val toRead = TechWatch.feedsToRead(
            all, newsDao.sources().toSet(), newsDao.lastFetch(), lastFailure, now, force,
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
        // A feed taken out of the list takes its headlines and its status with it.
        val urls = all.map { it.url }.toSet()
        val kept = statuses.filterKeys { it in urls }
        if (kept != stored) preferences.setWatchFeedStatus(FeedStatuses.encode(kept))
        newsDao.deleteOtherSources(urls.toList())
    }

    /** Null when the feed was read (its cached headlines replaced), else why it wasn't. */
    private suspend fun fetch(feed: Feed, now: Long): String? = try {
        val xml = loader.download(feed.url, NetPurpose.WATCH).html
        val channel = RssParser().parse(xml)
        val articles = TechWatch.articlesOf(feed, channel.items.map { FeedItem(it.title, it.link, it.pubDate) }, now)
        if (articles.isNotEmpty()) {
            database.withTransaction {
                newsDao.deleteSource(feed.url)
                newsDao.insertAll(articles.map { NewsArticleEntity(it.url, it.title, it.source, null, it.publishedAt, it.fetchedAt) })
            }
            null
        } else {
            FeedStatuses.problemOf(null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A feed couldn't be read: ${e.javaClass.simpleName}")
        FeedStatuses.problemOf(e)
    }

    private companion object {
        const val TAG = "TechWatch"
    }
}
