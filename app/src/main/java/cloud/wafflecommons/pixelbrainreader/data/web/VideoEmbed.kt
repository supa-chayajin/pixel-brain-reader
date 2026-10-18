package cloud.wafflecommons.pixelbrainreader.data.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Video pages (YouTube, Vimeo, Dailymotion) are players, not articles: they are read through the
 * platform's oEmbed endpoint and become a card — the thumbnail linking to the video, the channel,
 * the description. Pure: the loader does the fetching.
 */
object VideoEmbed {

    private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")
    private val DIGITS = Regex("""\d+""")
    private val SPACES = Regex("""\s+""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The video's own address (no share tracking), or null when [url] isn't a video page we know. */
    fun canonical(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        fun youtube(id: String?) = id?.takeIf { YOUTUBE_ID.matches(it) }?.let { "https://www.youtube.com/watch?v=$it" }
        return when (host) {
            "youtu.be" -> youtube(segments.firstOrNull())
            "youtube.com", "music.youtube.com" -> when (segments.firstOrNull()) {
                "watch" -> youtube(query(uri.rawQuery)["v"])
                "shorts", "live", "embed" -> youtube(segments.getOrNull(1))
                else -> null
            }
            "vimeo.com" -> segments.singleOrNull()?.takeIf { DIGITS.matches(it) }?.let { "https://vimeo.com/$it" }
            "dailymotion.com" -> segments.takeIf { it.firstOrNull() == "video" }?.getOrNull(1)?.let { "https://www.dailymotion.com/video/$it" }
            "dai.ly" -> segments.firstOrNull()?.let { "https://www.dailymotion.com/video/$it" }
            else -> null
        }
    }

    /** The oEmbed endpoint describing [canonical]. */
    fun endpoint(canonical: String): String? {
        val encoded = URLEncoder.encode(canonical, Charsets.UTF_8)
        return when {
            canonical.startsWith("https://www.youtube.com/") -> "https://www.youtube.com/oembed?format=json&url=$encoded"
            canonical.startsWith("https://vimeo.com/") -> "https://vimeo.com/api/oembed.json?url=$encoded"
            canonical.startsWith("https://www.dailymotion.com/") -> "https://www.dailymotion.com/services/oembed?format=json&url=$encoded"
            else -> null
        }
    }

    /** The video's clip from the oEmbed answer, with the page's [description] when it could be read. */
    fun clip(oEmbed: String, canonical: String, description: String?): WebClip? {
        val fields = runCatching { json.parseToJsonElement(oEmbed) as? JsonObject }.getOrNull() ?: return null
        fun field(key: String) = runCatching { fields[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.ifEmpty { null }
        val title = field("title") ?: return null
        val author = field("author_name")
        val provider = field("provider_name")
        val markdown = buildString {
            field("thumbnail_url")?.let { append("[![").append(title.replace('[', '(').replace(']', ')')).append("](").append(it).append(")](").append(canonical).append(")\n\n") }
            append("▶ [Voir la vidéo").append(provider?.let { " sur $it" }.orEmpty()).append("](").append(canonical).append(")")
            author?.let { append(" — ").append(it) }
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
        }
        return WebClip(
            url = canonical,
            title = title,
            siteName = provider,
            author = author,
            published = null,
            description = description,
            heroImage = null,
            markdown = markdown,
            words = markdown.split(SPACES).count { word -> word.any(Char::isLetterOrDigit) },
            isVideo = true
        )
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=', "").ifEmpty { return@mapNotNull null }
        key to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8)
    }.toMap()
}
