package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.MarkdownLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import javax.inject.Inject
import javax.inject.Singleton
import java.io.StringReader

@Singleton
class NewsRepository @Inject constructor(
    private val client: OkHttpClient
) {

    suspend fun getTopHeadlines(): List<MarkdownLink> = withContext(Dispatchers.IO) {
        val feeds = listOf(
            "https://feeds.feedburner.com/blogspot/hsDu" to "Android",
            "https://www.php.net/news.rss" to "PHP"
        )
        
        val allNews = mutableListOf<MarkdownLink>()
        
        feeds.forEach { (url, source) ->
             try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val xml = response.body?.string()
                
                if (xml != null) {
                    val items = parseRss(xml, source)
                    allNews.addAll(items.take(2)) // Take top 2 from each
                }
            } catch (e: Exception) {
                Log.e("NewsRepository", "Failed to fetch $source", e)
            }
        }
        
        // Return Top 3 Total
        allNews.take(3)
    }

    private fun parseRss(xml: String, source: String): List<MarkdownLink> {
        val items = mutableListOf<MarkdownLink>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            
            var eventType = parser.eventType
            var title = ""
            var link = ""
            var inItem = false
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) {
                            inItem = true
                            title = ""
                            link = ""
                        } else if (inItem) {
                            if (tagName.equals("title", ignoreCase = true)) {
                                title = parser.nextText()
                            } else if (tagName.equals("link", ignoreCase = true)) {
                                link = parser.nextText()
                                // Atom feeds might have link as attribute href
                                if (link.isBlank()) { 
                                     link = parser.getAttributeValue(null, "href") ?: ""
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items.add(MarkdownLink("[$source] $title", link))
                            }
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
             Log.e("NewsRepository", "RSS Parse Error", e)
        }
        return items
    }
}
