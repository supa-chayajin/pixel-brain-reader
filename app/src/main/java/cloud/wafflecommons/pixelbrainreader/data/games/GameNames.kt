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
     * The page a PSN title belongs to: a page name equal to the title's, or the first words of it
     * ("LEGO® Star Wars™: The Skywalker Saga" → a page called "LEGO Star Wars"). Null when none, or
     * when two pages tie on the longest name.
     */
    fun pageForTitle(psnName: String, pages: List<GamePage>): GamePage? {
        val title = normalize(psnName)
        if (title.isEmpty()) return null
        return longest(pages) { candidate -> title == candidate || title.startsWith("$candidate ") }
    }

    /** The page a Timeline line names, anywhere in the line as whole words; null when none or tied. */
    fun pageInLine(line: String, pages: List<GamePage>): GamePage? {
        val text = " ${normalize(line)} "
        if (text.isBlank()) return null
        return longest(pages) { candidate -> text.contains(" $candidate ") }
    }

    private fun longest(pages: List<GamePage>, matches: (String) -> Boolean): GamePage? {
        val hits = pages.mapNotNull { page ->
            page.names.map(::normalize).filter { it.isNotEmpty() && matches(it) }.maxByOrNull { it.length }
                ?.let { page to it.length }
        }
        val best = hits.maxOfOrNull { it.second } ?: return null
        val winners = hits.filter { it.second == best }.map { it.first }.distinctBy { it.path }
        return winners.singleOrNull()
    }
}
