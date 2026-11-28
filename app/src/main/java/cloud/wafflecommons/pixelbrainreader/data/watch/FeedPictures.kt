package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Which feed pictures load, and how (13.4, F7), pure. Nothing here talks to the network. Any host
 * may serve one (an article's picture sits on a CDN more often than on its site), so the rules are
 * the address's own: https, no user info, the https port, and a public host as written
 * ([ImportUrlValidator.isSafe]); the loader's DNS then refuses a name that points at the LAN.
 */
object FeedPictures {

    /** A picture's bytes past this are refused: a thumbnail is far smaller. */
    const val MAX_BYTES = 3L * 1024 * 1024

    /** Redirects followed, each one checked before it is requested. */
    const val MAX_REDIRECTS = 3

    /** Pixels decoded: a 56 dp thumbnail up to 3.5×. */
    const val SIZE_PX = 200

    private const val HTTPS_PORT = 443

    /** https, no user info, port 443 or none, and ImportUrlValidator.isSafe (public literal hosts, length cap). Any host. */
    fun allowed(url: String?): Boolean {
        if (url.isNullOrBlank() || !ImportUrlValidator.isSafe(url)) return false
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null) return false
        return uri.port == -1 || uri.port == HTTPS_PORT
    }

    /**
     * The next hop of a redirect from [from] to [location] (a relative one resolved the way OkHttp
     * itself resolves it), when [allowed]; else null. Each hop passes the first address's rules
     * before it is requested.
     */
    fun redirectTarget(from: String, location: String?): String? {
        val raw = location?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val next = from.toHttpUrlOrNull()?.resolve(raw)?.toString() ?: return null
        return next.takeIf { allowed(it) }
    }

    /**
     * The first of [candidates] that is [allowed] once made absolute: trimmed, `//host/…` becomes
     * `https://host/…`, a relative path resolves against [link]. http is never upgraded: an http
     * picture, or a relative one under an http link, is no picture.
     */
    fun thumbnailOf(candidates: List<String>, link: String?): String? {
        val base = link?.trim()?.toHttpUrlOrNull()
        candidates.forEach { candidate ->
            val text = candidate.trim()
            if (text.isEmpty()) return@forEach
            val absolute = if (text.startsWith("//")) {
                "https:$text".toHttpUrlOrNull()
            } else {
                text.toHttpUrlOrNull() ?: base?.resolve(text)
            }?.toString() ?: return@forEach
            if (allowed(absolute)) return absolute
        }
        return null
    }
}
