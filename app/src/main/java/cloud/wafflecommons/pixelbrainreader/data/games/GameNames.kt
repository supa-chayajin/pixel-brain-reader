package cloud.wafflecommons.pixelbrainreader.data.games

/**
 * Matching game names (pure): a PSN title to a page, and a Timeline line to a page. Names are
 * compared normalised (lower case, ™ ® © dropped, every other non-letter, non-digit a space), as
 * whole words, the longest name winning: "ELDEN RING NIGHTREIGN" is the Nightreign page, not the
 * Elden Ring one. Spaces stand in for word boundaries, so the JVM tests and Android's ICU agree.
 */
object GameNames {

    fun normalize(name: String): String = buildString {
        for (ch in name.lowercase()) {
            when {
                ch == '™' || ch == '®' || ch == '©' -> Unit
                // An apostrophe joins its word ("demon's"), whichever quote the source used.
                ch == '\'' || ch == '’' -> Unit
                ch.isLetterOrDigit() -> append(ch)
                else -> append(' ')
            }
        }
    }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * The page a console title (PSN's or, 13.3, Xbox's) belongs to: a page name equal to the
     * title's, or the first words of it ("BRICK® Wars™: The Saga" → a page called
     * "Brick Wars"). A page marked `game_match: exact` ([GamePage.exactName]) answers to its exact
     * names only. Null when none, or when two pages tie on the longest name.
     */
    fun pageForTitle(psnName: String, pages: List<GamePage>): GamePage? {
        val title = normalize(psnName)
        if (title.isEmpty()) return null
        return longest(pages) { page, candidate ->
            title == candidate || (!page.exactName && title.startsWith("$candidate "))
        }
    }

    /**
     * True when some page answers to [title] by its names: [pageForTitle], or a name equal to it or
     * (on a page not marked exact) the first words of it, a tie between two pages included. What a
     * title needs to get no page of its own (XB4).
     */
    internal fun answered(title: String, pages: List<GamePage>): Boolean {
        if (pageForTitle(title, pages) != null) return true
        val name = normalize(title)
        if (name.isEmpty()) return false
        return pages.any { page ->
            page.names.map(::normalize).any { it.isNotEmpty() && (name == it || (!page.exactName && name.startsWith("$it "))) }
        }
    }

    /**
     * The page [title] looks like (13.4, the owner's call): no page answers it, but it is the first
     * whole words of a page's name or alias ("Glass Tower" beside a "Glass Tower Online" page: a
     * console that listed the game again under a shorter name, or the base game of a sequel's page).
     * The fold asks; nothing is linked on its own. The page whose such name is shortest wins, then by
     * title; null when none.
     */
    fun lookalike(title: String, pages: List<GamePage>): GamePage? {
        val name = normalize(title)
        if (name.isEmpty() || answered(title, pages)) return null
        val prefix = "$name "
        return pages.mapNotNull { page ->
            page.names.map(::normalize).filter { it.isNotEmpty() && it.startsWith(prefix) }.minOfOrNull { it.length }?.let { page to it }
        }.minWithOrNull(compareBy<Pair<GamePage, Int>>({ it.second }, { it.first.title.lowercase() }))?.first
    }

    /** The page a Timeline line names, anywhere in the line as whole words; null when none or tied. */
    fun pageInLine(line: String, pages: List<GamePage>): GamePage? {
        val text = " ${normalize(line)} "
        if (text.isBlank()) return null
        return longest(pages) { _, candidate -> text.contains(" $candidate ") }
    }

    private fun longest(pages: List<GamePage>, matches: (page: GamePage, candidate: String) -> Boolean): GamePage? {
        val hits = pages.mapNotNull { page ->
            page.names.map(::normalize).filter { it.isNotEmpty() && matches(page, it) }.maxByOrNull { it.length }
                ?.let { page to it.length }
        }
        val best = hits.maxOfOrNull { it.second } ?: return null
        val winners = hits.filter { it.second == best }.map { it.first }.distinctBy { it.path }
        return winners.singleOrNull()
    }
}
