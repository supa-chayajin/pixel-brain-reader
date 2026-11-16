package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import java.net.URI
import java.net.URLDecoder

/** A game's map (13.2): the address the app's map browser opens, its name, and the site's. */
data class GameMap(val url: String, val label: String, val site: String)

/**
 * A game page's maps (13.2, pure): the `game_maps` list of its frontmatter, each entry an https
 * address or `Label | https://…` (the label says what the page is: "Interactive map", "Maps
 * (images)"). Only public https addresses are kept, by the import guard's own check; anything else
 * stays in the note and out of the app. The first one is the game's main map.
 */
object GameMaps {

    const val KEY = "game_maps"
    private const val SEPARATOR = " | "

    fun parse(entries: List<String>): List<GameMap> = entries.mapNotNull(::entry).distinctBy { it.url }

    private fun entry(raw: String): GameMap? {
        val text = raw.trim()
        val cut = text.lastIndexOf(SEPARATOR)
        val given = if (cut >= 0) text.substring(0, cut).trim() else ""
        val url = if (cut >= 0) text.substring(cut + SEPARATOR.length).trim() else text
        if (!url.startsWith("https://", ignoreCase = true) || !ImportUrlValidator.isSafe(url)) return null
        val site = siteOf(url) ?: return null
        return GameMap(url = url, label = given.ifEmpty { pageName(url) ?: site }, site = site)
    }

    /** The site's name, from the domain before its suffix: `somegame.wiki.example.com` → "Example". */
    fun siteOf(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts[parts.size - 2].replaceFirstChar { it.uppercase() }
    }

    /** The address's last path segment as words: `/Interactive+Map` → "Interactive Map". */
    private fun pageName(url: String): String? {
        val last = runCatching { URI(url).rawPath }.getOrNull().orEmpty().trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return decoded.replace('_', ' ').trim().takeIf { it.isNotEmpty() }
    }
}
