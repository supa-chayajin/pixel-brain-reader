package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.PageCandidate
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState

/**
 * The Xbox titles' pages (pure, 13.3): links found by the sync, kept in `links.json` apart from
 * PSN's ([GameLinks.xbox]), and the titles that get a page of their own (XB4). A 360 game played
 * on a One is listed under its 360 id with a modern id: the two are siblings, one game.
 */
object XboxLinking {

    /**
     * New Xbox links for titles no page claims yet: a linked sibling (modernTitleId either way) first,
     * then the name; hidden titles left out. The two passes repeat until one adds nothing (13.4), so a
     * chain of siblings follows the one its name linked, whatever the history's order.
     */
    fun autoLinked(links: GameLinks, current: XboxSnapshot, pages: List<GamePage>): GameLinks {
        var next = links
        repeat(current.titles.size + 1) {
            val passed = passes(next, current, pages)
            if (passed == next) return next
            next = passed
        }
        return next
    }

    private fun passes(links: GameLinks, current: XboxSnapshot, pages: List<GamePage>): GameLinks {
        var next = links
        current.titles.forEach { title ->
            if (!open(next, title.titleId)) return@forEach
            val page = siblingPage(next, title, current) ?: GameNames.pageForTitle(title.name, pages)?.path ?: return@forEach
            next = next.linkConsole(GameConsole.XBOX, title.titleId, page)
        }
        // A sibling linked by its name above takes along one listed before it.
        current.titles.forEach { title ->
            if (!open(next, title.titleId)) return@forEach
            val page = siblingPage(next, title, current) ?: return@forEach
            next = next.linkConsole(GameConsole.XBOX, title.titleId, page)
        }
        return next
    }

    /** The XB4 candidates: played games with no Xbox link (dangling counts), not hidden, visible on the profile. */
    fun candidates(links: GameLinks, current: XboxSnapshot): List<PageCandidate> =
        current.titles
            .filter { it.visible && open(links, it.titleId) }
            .map { title -> PageCandidate(title.titleId, title.name, siblings(title, current), XboxTimes.instant(title.lastPlayed)) }

    /** No link yet (a link to a deleted page counts as one: the owner deleted it) and not hidden. */
    private fun open(links: GameLinks, titleId: String): Boolean =
        links.consolePage(GameConsole.XBOX, titleId) == null && !links.isHidden(GameConsole.XBOX, titleId)

    private fun siblingPage(links: GameLinks, title: XboxTitleState, current: XboxSnapshot): String? =
        siblings(title, current).firstNotNullOfOrNull { links.consolePage(GameConsole.XBOX, it) }

    /** The other ids of the same game: its modern id, and the titles whose modern id it is. */
    private fun siblings(title: XboxTitleState, current: XboxSnapshot): List<String> {
        val modern = title.modernTitleId?.takeIf { it.isNotBlank() && it != title.titleId }
        val legacy = current.titles.filter { it.titleId != title.titleId && it.modernTitleId == title.titleId }.map { it.titleId }
        return (listOfNotNull(modern) + legacy).distinct()
    }
}
