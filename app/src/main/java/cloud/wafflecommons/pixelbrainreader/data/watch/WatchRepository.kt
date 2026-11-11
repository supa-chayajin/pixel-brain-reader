package cloud.wafflecommons.pixelbrainreader.data.watch

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.web.WebPageLoader
import com.prof18.rssparser.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * parser's own client would skip the guard). A feed that fails keeps its last headlines.
 */
@Singleton
class WatchRepository @Inject constructor(
    private val feeds: FeedConfigRepository,
    private val newsDao: NewsDao,
    private val database: AppDatabase,
    private val loader: WebPageLoader
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

    private suspend fun refreshIfDue(all: List<Feed>, force: Boolean) = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!TechWatch.needsRefresh(newsDao.lastFetch(), lastFailure, now, force)) return@withLock
        val read = coroutineScope { all.map { feed -> async { fetch(feed, now) } }.awaitAll() }
        lastFailure = if (read.none { it }) now else null
        // A feed taken out of the list takes its headlines with it.
        newsDao.deleteOtherSources(all.map { it.url })
    }

    /** True when the feed was read; its cached headlines are replaced only then. */
    private suspend fun fetch(feed: Feed, now: Long): Boolean = try {
        val xml = loader.download(feed.url).html
        val channel = RssParser().parse(xml)
        val articles = TechWatch.articlesOf(feed, channel.items.map { FeedItem(it.title, it.link, it.pubDate) }, now)
        if (articles.isNotEmpty()) {
            database.withTransaction {
                newsDao.deleteSource(feed.url)
                newsDao.insertAll(articles.map { NewsArticleEntity(it.url, it.title, it.source, null, it.publishedAt, it.fetchedAt) })
            }
        }
        articles.isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A feed couldn't be read: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "TechWatch"
    }
}
