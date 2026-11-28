package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot

/**
 * PSN's links found by name and concept (pure, 13.4: to a fixed point). A title no page claims yet
 * takes the page of a sibling version already linked (the PS4 and PS5 ids of one game), else the
 * page its name answers to; hidden titles stay out. The passes repeat until one adds no link, so a
 * version listed before the sibling its name links follows it in the same sync, never getting a page
 * of its own.
 */
internal object PsnLinking {

    /** Today's PsnSync.autoLinked, repeated until a pass adds no link (at most titles + 1 passes). */
    fun autoLinked(links: GameLinks, current: PsnSnapshot, pages: List<GamePage>): GameLinks {
        var next = links
        repeat(current.titles.size + 1) {
            val passed = pass(next, current, pages)
            if (passed == next) return next
            next = passed
        }
        return next
    }

    /** One pass, the titles in PSN's order (the most recently played first). */
    private fun pass(links: GameLinks, current: PsnSnapshot, pages: List<GamePage>): GameLinks {
        var next = links
        current.titles.forEach { title ->
            if (next.pageOf(title.titleId) != null || title.titleId in next.hidden) return@forEach
            // A sibling version already linked (the PS4 and PS5 ids of one game) takes this one along.
            val sibling = title.conceptTitleIds.firstNotNullOfOrNull { next.pageOf(it) }
            val page = sibling ?: GameNames.pageForTitle(title.name, pages)?.path ?: return@forEach
            next = next.link(title.titleId, page)
        }
        return next
    }
}
