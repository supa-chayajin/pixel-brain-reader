package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate

/** Why a map edit was refused, or that it went through (ME1–ME3). */
enum class MapEditResult { SAVED, NOT_PUBLIC_HTTPS, ALREADY_THERE, GONE, READ_ONLY, FAILED }

/** A map edit on a page's text: the text to save (the same text when nothing changes), or why not. */
sealed interface MapEdit {
    data class Saved(val content: String) : MapEdit
    data class Refused(val reason: MapEditResult) : MapEdit
}

/**
 * The raw-entry edits of a page's `game_maps` (13.3, ME1–ME3, pure). The list stays the vault's: one
 * item per line, two spaces, `- `, a double-quoted entry, the first readable one being the main map.
 * Maps are named by their address, never by a list index that may have moved. Untouched entries
 * keep their exact line; an entry the app can't read (not https, a private address, any text) keeps
 * its line and its place. An inline list or a scalar becomes a block list on the first change, and a
 * missing key is added. Each change also dates `date_updated` when the page has that key; nothing
 * else moves, and a change that changes nothing gives the page back as it was.
 */
object GameMapEdits {

    private const val DATE_UPDATED = "date_updated"

    /** A new entry's line, as the vault writes it. */
    fun line(url: String, label: String?): String = "  - " + VaultSchema.doubleQuoted(GameMaps.text(url, label))

    /** [url] (with an optional [label]) added after every entry. */
    fun add(content: String, url: String, label: String?, today: LocalDate): MapEdit {
        val address = url.trim()
        if (!GameMaps.accepts(address)) return MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS)
        val items = items(content)
        if (items.any { urlOf(it) == address }) return MapEdit.Refused(MapEditResult.ALREADY_THERE)
        return write(content, items, items.map { it.line } + line(address, label), today)
    }

    /**
     * The map at [oldUrl] given the address [url] and the label [label], in its place; the same text
     * keeps its line as typed, and later entries of [oldUrl] go.
     */
    fun change(content: String, oldUrl: String, url: String, label: String?, today: LocalDate): MapEdit {
        val address = url.trim()
        if (!GameMaps.accepts(address)) return MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS)
        val old = oldUrl.trim()
        val items = items(content)
        val urls = items.map(::urlOf)
        val at = urls.indexOf(old)
        if (at < 0) return MapEdit.Refused(MapEditResult.GONE)
        if (address != old && address in urls) return MapEdit.Refused(MapEditResult.ALREADY_THERE)
        val text = GameMaps.text(address, label)
        val lines = items.indices.mapNotNull { i ->
            when {
                i == at -> if (items[i].value == text) items[i].line else line(address, label)
                urls[i] == old -> null
                else -> items[i].line
            }
        }
        return write(content, items, lines, today)
    }

    /** Every entry of [url] removed. */
    fun remove(content: String, url: String, today: LocalDate): MapEdit {
        val address = url.trim()
        val items = items(content)
        val kept = items.filter { urlOf(it) != address }
        if (kept.size == items.size) return MapEdit.Refused(MapEditResult.GONE)
        return write(content, items, kept.map { it.line }, today)
    }

    /**
     * [url]'s map made the main one: taken out with its duplicates and put back just before the first
     * entry the app reads, so an unreadable entry keeps its place. Already the main map, it stays
     * where it is (only its duplicates go).
     */
    fun makeMain(content: String, url: String, today: LocalDate): MapEdit {
        val address = url.trim()
        val items = items(content)
        val urls = items.map(::urlOf)
        val at = urls.indexOf(address)
        if (at < 0) return MapEdit.Refused(MapEditResult.GONE)
        val duplicated = urls.count { it == address } > 1
        if (urls.indexOfFirst { it != null } == at && !duplicated) return MapEdit.Saved(content)
        val rest = items.indices.filter { urls[it] != address }
        val firstReadable = rest.indexOfFirst { urls[it] != null }
        // Already the main map (nothing readable before it): it keeps its place among the rest.
        val before = rest.count { it < at }
        val insertAt = if (firstReadable < 0) before else minOf(firstReadable, before)
        val lines = rest.map { items[it].line }.toMutableList().apply { add(insertAt, items[at].line) }
        return write(content, items, lines, today)
    }

    /** Entries of the page's `game_maps` the app can't open (kept as typed). */
    fun unreadable(content: String): Int = items(content).count { urlOf(it) == null }

    private fun items(content: String): List<FrontmatterLines.ListItem> = FrontmatterLines.listItems(content, GameMaps.KEY)

    /** The address an entry opens, or null when the app can't read it. */
    private fun urlOf(item: FrontmatterLines.ListItem): String? = GameMaps.parse(listOf(item.value)).firstOrNull()?.url

    private fun write(content: String, items: List<FrontmatterLines.ListItem>, lines: List<String>, today: LocalDate): MapEdit {
        if (lines == items.map { it.line }) return MapEdit.Saved(content)
        var next = FrontmatterLines.setListLines(content, GameMaps.KEY, lines)
        if (FrontmatterLines.get(next, DATE_UPDATED) != null) {
            next = FrontmatterLines.set(next, DATE_UPDATED, VaultSchema.singleQuoted(today.toString()))
        }
        return MapEdit.Saved(next)
    }
}
