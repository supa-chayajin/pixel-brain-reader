package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxImages
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Which console pictures the app may load, and how (13.4, GP2), pure. Only the consoles' own image
 * hosts, over https: the address comes from a vault file a desktop edit can change, so anything
 * else falls back to the game's initials. Nothing here talks to the network.
 */
object GamePictures {

    /** The consoles' image hosts, exact (a sub-domain or a look-alike is another host). */
    val HOSTS: Set<String> = setOf("image.api.playstation.com", "store-images.s-microsoft.com", "images-eds-ssl.xboxlive.com")

    /** The pixels asked for where a host takes a size: a game's page shows its cover at about 176 dp. */
    const val SIZE_HINT = 512

    /** The Now playing card's (13.4 review, the owner's choice): the card spans the window, 2 to 3 times a page's cover. */
    const val HERO_SIZE_HINT = 1024

    /** A picture's bytes past this are refused (a console's cover is well under it). */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** Redirects followed, each one checked before it is requested (the design's open point a). */
    const val MAX_REDIRECTS = 3

    private const val HTTPS_PORT = 443
    private const val PLAYSTATION = "image.api.playstation.com"
    private val MICROSOFT = setOf("store-images.s-microsoft.com", "images-eds-ssl.xboxlive.com")

    /** https, an exact host of [HOSTS] (lowercased), no user info, no port but 443, and ImportUrlValidator.isSafe. */
    fun allowed(url: String?): Boolean {
        if (url.isNullOrBlank() || !ImportUrlValidator.isSafe(url)) return false
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != HTTPS_PORT) return false
        return uri.host?.lowercase() in HOSTS
    }

    /**
     * A snapshot's address as the loader may use it: trimmed, Xbox's through [XboxImages.https] first
     * (its http hosts made https, never http), then [allowed]; null otherwise. The reader applies the
     * rewrite itself, so an older or hand-edited snapshot can't bring http in.
     */
    fun consoleUrl(raw: String?, console: GameConsole): String? {
        val url = when (console) {
            GameConsole.PSN -> raw?.trim()?.takeIf { it.isNotEmpty() }
            GameConsole.XBOX -> XboxImages.https(raw)
        } ?: return null
        return url.takeIf { allowed(it) }
    }

    /**
     * [url] asking about [px] pixels: PlayStation `w=`, Microsoft's two hosts `w=` and `h=`, added to
     * the query unless a `w=` is already there; any other host as is. An `h=` already there isn't
     * added twice. The rest of the address is kept as written.
     */
    fun sized(url: String, px: Int = SIZE_HINT): String {
        val host = try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return url
        val microsoft = host in MICROSOFT
        if (host != PLAYSTATION && !microsoft) return url
        val fragmentAt = url.indexOf('#')
        val base = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val queryAt = base.indexOf('?')
        val names = if (queryAt < 0) emptySet<String>() else {
            base.substring(queryAt + 1).split('&').map { it.substringBefore('=').lowercase() }.toSet()
        }
        if ("w" in names) return url
        val added = buildList {
            add("w=$px")
            if (microsoft && "h" !in names) add("h=$px")
        }.joinToString("&")
        val joined = when {
            queryAt < 0 -> "$base?$added"
            base.endsWith("?") || base.endsWith("&") -> base + added
            else -> "$base&$added"
        }
        return joined + fragment
    }

    /**
     * The next hop of a redirect from [from] to [location] (relative ones resolved, the way OkHttp
     * itself resolves them), when [allowed]; else null. Each hop passes the first address's rules
     * before it is requested.
     */
    fun redirectTarget(from: String, location: String?): String? {
        val raw = location?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val next = from.toHttpUrlOrNull()?.resolve(raw)?.toString() ?: return null
        return next.takeIf { allowed(it) }
    }
}
