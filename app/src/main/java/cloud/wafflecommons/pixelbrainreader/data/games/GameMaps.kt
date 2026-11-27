package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import java.net.URI
import java.net.URLDecoder

/**
 * A game's map (13.2): the address the app's map browser opens, its name, and the site's.
 * [given]: the label as typed before ` | ` (null when the entry has none), for editing (13.3).
 */
data class GameMap(val url: String, val label: String, val site: String, val given: String? = null)

/** A link shared from a browser, as a map (ME2): the address, the shared title as a label. */
data class MapOffer(val url: String, val label: String?)

/**
 * A game page's maps (13.2, pure): the `game_maps` list of its frontmatter, each entry an https
 * address or `Label | https://…` (the label says what the page is: "Interactive map", "Maps
 * (images)"). Only public https addresses are kept, by the import guard's own check; anything else
 * stays in the note and out of the app. The first one is the game's main map. 13.3 adds what the
 * edits need: the check alone ([accepts]), a typed label cleaned ([cleanLabel]), the entry's text
 * ([text]), the map an entry would make ([preview]) and a shared link as a map ([fromShare]).
 */
object GameMaps {

    const val KEY = "game_maps"
    private const val SEPARATOR = " | "
    private const val MAX_LABEL = 80

    fun parse(entries: List<String>): List<GameMap> = entries.mapNotNull(::entry).distinctBy { it.url }

    private fun entry(raw: String): GameMap? {
        val text = raw.trim()
        val cut = text.lastIndexOf(SEPARATOR)
        val given = if (cut >= 0) text.substring(0, cut).trim() else ""
        val url = if (cut >= 0) text.substring(cut + SEPARATOR.length).trim() else text
        if (!accepts(url)) return null
        val site = siteOf(url) ?: return null
        return GameMap(url = url, label = given.ifEmpty { pageName(url) ?: site }, site = site, given = given.ifEmpty { null })
    }

    /**
     * True for a public https address the map browser may open (the import guard's check), with no
     * user name or password before its host: the address is written into the vault and pushed, and
     * the browser would hand them to the site.
     */
    fun accepts(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && ImportUrlValidator.isSafe(url) && siteOf(url) != null &&
            runCatching { URI(url).rawUserInfo == null }.getOrDefault(false)

    /**
     * A typed label: whitespace runs one space, control characters and bidi controls dropped,
     * trimmed, at most 80 characters; null when nothing is left. Hand-written, no regex. A label can
     * be a site's page title (a share, the map browser), and the Maps card shows "<label> · <site>":
     * a right-to-left override there would turn the site's name around.
     */
    fun cleanLabel(raw: String?): String? {
        if (raw == null) return null
        val out = StringBuilder()
        var space = false
        for (ch in raw) {
            when {
                ch.isWhitespace() -> space = true
                ch.isISOControl() -> Unit
                // The bidi marks, embeddings, overrides and isolates.
                ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' || ch == '\u061C' -> Unit
                else -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.append(ch)
                }
            }
        }
        var label = if (out.length > MAX_LABEL) out.substring(0, MAX_LABEL) else out.toString()
        // A cut never leaves half of an emoji behind.
        if (label.isNotEmpty() && label.last().isHighSurrogate()) label = label.dropLast(1)
        return label.trimEnd().ifEmpty { null }
    }

    /** The entry's text: `<label> | <url>`, or `<url>` without a label (the label through [cleanLabel], the address trimmed). */
    fun text(url: String, label: String?): String {
        val address = url.trim()
        val name = cleanLabel(label) ?: return address
        return name + SEPARATOR + address
    }

    /** What the map would show as, before saving (the dialog's preview); null when refused. */
    fun preview(url: String, label: String?): GameMap? = parse(listOf(text(url, label))).firstOrNull()

    /**
     * ME2: the shared address when it can be a map, with the shared title as the label. The shared
     * text is untrusted: only the address and the title are read, and the address passes [accepts].
     */
    fun fromShare(url: String?, title: String?): MapOffer? =
        url?.trim()?.takeIf(::accepts)?.let { MapOffer(it, cleanLabel(title)) }

    /** The site's name, from the domain before its suffix: `somegame.wiki.example.com` → "Example". */
    fun siteOf(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts[parts.size - 2].replaceFirstChar { it.uppercase() }
    }

    /**
     * The address's last path segment as words: `/Interactive+Map` → "Interactive Map". Cleaned like
     * a typed label: the site chose the address, and a decoded segment can hold bidi controls too.
     */
    private fun pageName(url: String): String? {
        val last = runCatching { URI(url).rawPath }.getOrNull().orEmpty().trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return cleanLabel(decoded.replace('_', ' '))
    }
}
