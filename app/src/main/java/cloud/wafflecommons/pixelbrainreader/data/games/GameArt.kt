package cloud.wafflecommons.pixelbrainreader.data.games

import java.io.File

/** Where a game's picture comes from (P5, P7). */
enum class ArtSource { OWN, PSN, XBOX }

/**
 * A game's picture as every surface shows it (13.4): the owner's, else PlayStation's, else Xbox's,
 * else the [initials] in [key]'s shape and tone. Never a colour taken from the picture (P6): the
 * tone is the device's, the picture sits on top.
 */
data class GameArt(
    /** The shape and tone key: the page's title (badgeShapeFor, toneFor), as the 13.2 badges used. */
    val key: String,
    val title: String,
    /** Up to two letters or digits; empty: the pad icon. */
    val initials: String,
    val ownFile: File? = null,
    /** An https address on an allowed host, without the size hint. */
    val consoleUrl: String? = null,
    val source: ArtSource? = null,
    /**
     * When [ownFile] last changed (GamePictureSource reads it, 0 when unknown): a picture replaced at
     * the same path (the page's own `(cover).jpg`) is then another picture, and loads again on screen.
     */
    val ownFileStamp: Long = 0L
)

/** How a game's picture is chosen (13.4, GP1), pure. */
object GameArtRules {

    /**
     * [page]'s picture: the owner's [ownFile] first (P5); else the first of [psn] whose address the
     * loader may use; else the first such of [xbox] (P7); else the initials alone. [psn] and [xbox]
     * are the page's linked titles ([titlesOf]).
     */
    fun of(page: GamePage, ownFile: File?, psn: List<PsnTitleState>, xbox: List<XboxTitleState>): GameArt {
        val base = initialsOnly(page.title)
        if (ownFile != null) return base.copy(ownFile = ownFile, source = ArtSource.OWN)
        psn.firstNotNullOfOrNull { GamePictures.consoleUrl(it.imageUrl, GameConsole.PSN) }
            ?.let { return base.copy(consoleUrl = it, source = ArtSource.PSN) }
        xbox.firstNotNullOfOrNull { GamePictures.consoleUrl(it.imageUrl, GameConsole.XBOX) }
            ?.let { return base.copy(consoleUrl = it, source = ArtSource.XBOX) }
        return base
    }

    /** A console title without a page (the folds). */
    fun forTitle(name: String, imageUrl: String?, console: GameConsole): GameArt {
        val url = GamePictures.consoleUrl(imageUrl, console) ?: return initialsOnly(name)
        val source = when (console) {
            GameConsole.PSN -> ArtSource.PSN
            GameConsole.XBOX -> ArtSource.XBOX
        }
        return initialsOnly(name).copy(consoleUrl = url, source = source)
    }

    fun initialsOnly(title: String): GameArt = GameArt(key = title, title = title, initials = initials(title))

    /**
     * Up to two initials, uppercased: one word gives its first letter or digit, two or more the first
     * of the first two words. A word is a run of letters and digits, read by code point. An
     * apostrophe (either quote) joins its word as GameNames reads names ("Lantern's"), and ™ ® ©,
     * the invisible formatting characters, the other format characters (joiners, emoji tags) and
     * combining marks are skipped without ending a word. Empty when there is no letter or digit:
     * the pad icon shows instead. Hand-written: no regex word boundaries.
     */
    fun initials(title: String): String {
        val firsts = ArrayList<Int>(2)
        var inWord = false
        var i = 0
        while (i < title.length && firsts.size < 2) {
            val cp = title.codePointAt(i)
            i += Character.charCount(cp)
            when {
                skipped(cp) -> Unit
                Character.isLetterOrDigit(cp) -> {
                    if (!inWord) firsts += cp
                    inWord = true
                }
                else -> inWord = false
            }
        }
        return buildString { firsts.forEach { appendCodePoint(Character.toUpperCase(it)) } }
    }

    /** A character that neither belongs to a word nor ends one. */
    private fun skipped(cp: Int): Boolean {
        if (cp == '\''.code || cp == '’'.code || cp == '™'.code || cp == '®'.code || cp == '©'.code) return true
        if (Character.isBmpCodePoint(cp) && isInvisibleFormat(cp.toChar())) return true
        return when (Character.getType(cp)) {
            Character.FORMAT.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }
    }

    /** [page]'s linked titles of each console, in their links' order. */
    fun titlesOf(page: GamePage, links: GameLinks, psn: PsnSnapshot?, xbox: XboxSnapshot?): Pair<List<PsnTitleState>, List<XboxTitleState>> {
        val psnIds = links.consoleTitles(GameConsole.PSN, page.path)
        val xboxIds = links.consoleTitles(GameConsole.XBOX, page.path)
        val psnTitles = if (psnIds.isEmpty()) emptyList() else {
            val byId = firstById(psn?.titles.orEmpty()) { it.titleId }
            psnIds.mapNotNull { byId[it] }
        }
        val xboxTitles = if (xboxIds.isEmpty()) emptyList() else {
            val byId = firstById(xbox?.titles.orEmpty()) { it.titleId }
            xboxIds.mapNotNull { byId[it] }
        }
        return psnTitles to xboxTitles
    }

    /** What to load, in order: the owner's file, the console address with its size hint, the same without it; empty: the initials alone. */
    fun models(art: GameArt, sizeHint: Int = GamePictures.SIZE_HINT): List<Any> =
        listOfNotNull<Any>(art.ownFile, art.consoleUrl?.let { GamePictures.sized(it, sizeHint) }, art.consoleUrl).distinct()

    /** A snapshot's titles by id, the first of an id winning (as a lookup through the list would). */
    private fun <T> firstById(titles: List<T>, id: (T) -> String): Map<String, T> {
        val byId = HashMap<String, T>(titles.size)
        titles.forEach { byId.putIfAbsent(id(it), it) }
        return byId
    }
}
