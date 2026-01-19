package cloud.wafflecommons.pixelbrainreader.data.repository

import com.prof18.rssparser.RssParser
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity

@Singleton
class NewsRepository @Inject constructor(
    private val newsDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao
) {

    private val parser = RssParser()

    private val feeds = listOf(
        FeedSource("https://www.myastuce.fr/fr/rss/rss-feed", "[MyAstuce]"),
        FeedSource("https://actu.fr/76actu/rss.xml", "[76Actu]"),
        FeedSource("https://www.ouest-france.fr/rss/une", "[OuestFrance]"),
        FeedSource("https://feeds.feedburner.com/symfony/blog", "[Symfony]"),
        FeedSource("https://feed.laravel-news.com/", "[Laravel]"),
        FeedSource("https://php.watch/feed/news.xml", "[PHP]"),
    )

    suspend fun getTodayNews(forceRefresh: Boolean = false): List<NewsArticleEntity> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // 1. Check Cache for Today
        val todayStart = getStartOfDay()
        
        if (forceRefresh) {
            newsDao.deleteTodayNews(todayStart)
        } else {
            val cached = newsDao.getTodayNews(todayStart)
            if (cached.isNotEmpty()) {
                return@withContext cached
            }
        }

        // 2. Fetch Fresh if Cache Empty
        val deferredResults = feeds.map { source ->
            async {
                // Fetch and strictly take Top 2 per source
                fetchFeed(source).sortedByDescending { it.pubDate }.take(2)
            }
        }

        val freshNews = deferredResults.map { it.await() }
            .flatten()
            .shuffled() // Mix them up for variety in the UI list if desired, or keep sorted? User didn't specify sort order but "grouped by source". Ideally we return list and UI groups.
            // Actually user said "Group by source -> Take the top 2 latest articles per source".
            // If I shuffle here, grouping in UI is harder unless I sort there.
            // Let's NOT shuffle, or shuffle chunks. 
            // Better: just flatten. The UI will group them or show them.
            // Wait, Requirement: "Grouping by source is mandatory in the UI."
            // So I should return a list, and UI groups it. Or I return a Map? 
            // Repository usually returns List<Entity>. UI does grouping.
            // I will return the flattened list.
        
        // 3. Cache It
        val entities = freshNews.map { item ->
            cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity(
                url = item.url,
                title = item.title,
                sourceName = item.sourceName, // Use sourceName from Item
                thumbnailUrl = item.imageUrl,
                publishedDate = item.pubDate.time,
                fetchDate = System.currentTimeMillis()
            )
        }
        newsDao.insertAll(entities)

        entities
    }

    private suspend fun fetchFeed(source: FeedSource): List<RssItem> {
        return try {
            val channel = parser.getRssChannel(source.url)
            channel.items.map { item ->
                RssItem(
                    title = item.title ?: "No Title",
                    sourceName = source.tag,
                    pubDate = parseDate(item.pubDate),
                    url = item.link ?: "",
                    imageUrl = item.image
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseDate(dateString: String?): Date {
        if (dateString.isNullOrBlank()) return Date()

        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )

        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(dateString) ?: continue
            } catch (e: Exception) { /* Continue */ }
        }

        return Date()
    }
    
    private fun getStartOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private data class FeedSource(val url: String, val tag: String)
    private data class RssItem(val title: String, val sourceName: String, val pubDate: Date, val url: String, val imageUrl: String?)
}
