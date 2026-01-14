package cloud.wafflecommons.pixelbrainreader.data.repository

import com.prof18.rssparser.RssParser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepository @Inject constructor() {

    private val parser = RssParser()

    private val feeds = listOf(
        FeedSource("https://www.myastuce.fr/fr/rss/rss-feed", "[MyAstuce]"),
        FeedSource("https://actu.fr/76actu/rss.xml", "[76Actu]"),
        FeedSource("https://www.ouest-france.fr/rss/une", "[OuestFrance]"),
        FeedSource("https://feeds.feedburner.com/symfony/blog", "[Symfony]"),
        FeedSource("https://feed.laravel-news.com/", "[Laravel]"),
        FeedSource("https://php.watch/feed/news.xml", "[PHP]"),
        FeedSource("https://androidweekly.net/rss.xml", "[Android]")
    )

    suspend fun getNews(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val deferredResults = feeds.map { source ->
            async {
                // Fetch and strictly take Top 2 per source to ensure diversity
                fetchFeed(source).take(2)
            }
        }

        deferredResults.map { it.await() }
            .flatten()
            .shuffled() // Mix them up to ensure visibility of all sources regardless of time
            .map { "${it.tag} ${it.title}" }
    }

    private suspend fun fetchFeed(source: FeedSource): List<NewsItem> {
        return try {
            val channel = parser.getRssChannel(source.url)
            channel.items.map { item ->
                NewsItem(
                    title = item.title ?: "No Title",
                    tag = source.tag,
                    pubDate = parseDate(item.pubDate)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseDate(dateString: String?): Date {
        if (dateString.isNullOrBlank()) return Date() // Fallback to Now to ensure visibility

        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )

        for (pattern in patterns) {
            try {
                // Try US Locale first (most common for XML)
                return SimpleDateFormat(pattern, Locale.US).parse(dateString) ?: continue
            } catch (e: Exception) { /* Continue */ }
        }

        return Date() // Ultimate Fallback: Show it as "Fresh" rather than hiding it
    }

    private data class FeedSource(val url: String, val tag: String)
    private data class NewsItem(val title: String, val tag: String, val pubDate: Date)
}
