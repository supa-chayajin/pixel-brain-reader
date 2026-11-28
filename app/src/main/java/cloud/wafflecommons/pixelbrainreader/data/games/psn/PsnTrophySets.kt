package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySetFile

/**
 * A PSN title's trophy lists (pure, 13.4: every list of a title, not its first). A collection's one
 * title id can carry a list per game in it; before 13.4 the snapshot kept the first only, so the
 * titles kept that way are asked once more.
 */
internal object PsnTrophySets {

    /** Ids to ask for their lists: never asked first, then the titles kept with one list from before 13.4 (asked once more); at most [limit]. */
    fun toLookUp(played: List<PsnPlayedTitle>, before: Map<String, PsnTitleState>, limit: Int): List<String> {
        val ids = played.map { it.titleId }.distinct()
        val neverAsked = ids.filter { id -> before[id].let { old -> old == null || (old.trophySet == null && !old.trophyLookedUp) } }
        val oneList = ids.filter { id -> before[id]?.let { old -> old.trophySets.isEmpty() && old.trophySet != null } == true }
        return (neverAsked + oneList).distinct().take(limit.coerceAtLeast(0))
    }

    /**
     * A title's lists: those asked now; else the snapshot's `trophySets`; else its one `trophySet`;
     * else none. A list the snapshot named first stays first, so `trophySet` (what an older app
     * reads) doesn't move. A re-asked title whose answer is empty keeps its one list.
     */
    fun setsOf(titleId: String, old: PsnTitleState?, looked: Map<String, List<PsnTrophyTitle>>): List<String> {
        val asked = looked[titleId].orEmpty().map { it.npCommunicationId }.filter { it.isNotBlank() }.distinct()
        if (asked.isEmpty()) return old?.allTrophySets.orEmpty()
        val first = old?.trophySet
        return if (first != null && first in asked) listOf(first) + (asked - first) else asked
    }

    /** A list's file must be read again: none saved, empty, another version, or another earned count than PlayStation's. */
    fun moved(saved: TrophySetFile?, title: PsnTrophyTitle?): Boolean =
        saved == null || saved.trophies.isEmpty() ||
            (title != null && ((title.trophySetVersion != null && title.trophySetVersion != saved.version) || title.earnedTrophies.total != saved.earnedCount))
}
