package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.Instant
import java.time.LocalDate

/** A console title that may get a page: [siblings] are other ids of the same game. */
data class PageCandidate(val titleId: String, val name: String, val siblings: List<String> = emptyList(), val lastPlayed: Instant? = null)

/** Where new pages go, learned from the existing ones. */
data class GamePageLayout(val root: String, val ownFolder: Boolean, val separator: Char)

/** A page to create, before its text. */
data class PagePlan(val path: String, val title: String, val aliases: List<String>, val titleIds: List<String>, val console: GameConsole)

/** A page to create, with its text. */
data class NewGamePage(val plan: PagePlan, val content: String)

/**
 * Pages for played games (13.3, XB4, pure): a played console title that no page claims gets a page
 * with the Other template, where the owner's game pages already are and named the way they are. A
 * page is never planned over a note, one created for a short name answers to its exact names only
 * (`game_match: exact`), and a sync creates at most [MAX_PER_SYNC] per console, the games played
 * most recently first.
 */
object GamePageCreation {

    const val TEMPLATE_PATH = "99_System/Templates/T_Game_Other.md"
    const val MAX_PER_SYNC = 50

    private const val MAX_KEY = 80
    private const val OTHER_TEMPLATE = "other"

    /** Characters a file name can't hold on one of the desktops (or that Obsidian's links refuse). */
    private const val FORBIDDEN = "\\/:*?\"<>|#^[]"
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** Most recently played first; a title never played comes last, in the order given. */
    private val recentFirst: Comparator<PageCandidate> = compareBy(nullsLast(reverseOrder<Instant>())) { it.lastPlayed }

    /**
     * Where the existing pages are: when at least half of them sit in a folder named like their file
     * (`<root>/<Key>/<Key>.md`), a new page goes the same way under the parent folder holding the most
     * of them; otherwise next to the others, in the folder holding the most pages (ties: the
     * alphabetically first). Keys keep the separator most of the existing keys use (`_` unless more
     * use spaces than underscores). Null without a game page: nothing to learn from.
     */
    fun layout(pages: List<GamePage>): GamePageLayout? {
        if (pages.isEmpty()) return null
        val own = pages.filter { it.folder.isNotEmpty() && it.folder.substringAfterLast('/') == it.key }
        val ownFolder = own.size * 2 >= pages.size
        val root = if (ownFolder) mostCommon(own.map { it.folder.substringBeforeLast('/', "") }) else mostCommon(pages.map { it.folder })
        val keys = pages.map { it.key }
        val separator = if (keys.count { ' ' in it } > keys.count { '_' in it }) ' ' else '_'
        return GamePageLayout(root, ownFolder, separator)
    }

    /** A console's name as a page title: ™ ® © dropped, whitespace runs one space, trimmed. */
    fun pageTitle(consoleName: String): String {
        val out = StringBuilder()
        var space = false
        for (ch in consoleName) {
            when {
                ch == '™' || ch == '®' || ch == '©' -> Unit
                ch.isWhitespace() -> space = true
                ch.isISOControl() -> Unit
                else -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    /**
     * A title as a file name every clone of the vault takes: the characters a file system or a link
     * refuses and control characters made spaces, runs collapsed, dots and spaces dropped at the ends,
     * spaces turned into [separator], at most 80 characters; a Windows reserved name (CON, NUL, COM1…)
     * gets a trailing `_`. Empty when nothing is left.
     */
    fun keyOf(title: String, separator: Char): String {
        val words = StringBuilder()
        var space = false
        for (ch in title) {
            if (ch.isWhitespace() || ch.isISOControl() || ch in FORBIDDEN) {
                space = true
                continue
            }
            if (space && words.isNotEmpty()) words.append(' ')
            space = false
            words.append(ch)
        }
        var key = words.toString().trimEnd('.', ' ').trimStart('.', ' ').replace(' ', separator)
        if (key.length > MAX_KEY) {
            key = key.substring(0, MAX_KEY)
            if (key.last().isHighSurrogate()) key = key.dropLast(1)
            key = key.trimEnd('.', ' ', separator)
        }
        if (key.uppercase() in RESERVED) key += "_"
        return key
    }

    /**
     * The pages to create for [candidates]: one per game (candidates sharing a sibling id or the same
     * name make one page, all their ids linked to it), the most recently played first, at most
     * [limit]. A game some page already answers to gets none, nor one whose path is a note of
     * [takenPaths] (the notes under the layout's root, compared ignoring case: the desktop clones are
     * case-insensitive), or whose folder exists with another case, or that another plan of this call
     * takes. Empty without a layout.
     */
    fun plan(
        candidates: List<PageCandidate>,
        pages: List<GamePage>,
        takenPaths: Collection<String>,
        console: GameConsole,
        limit: Int = MAX_PER_SYNC
    ): List<PagePlan> {
        val layout = layout(pages) ?: return emptyList()
        val taken = takenPaths.mapTo(HashSet()) { it.lowercase() }
        val planned = HashSet<String>()
        val out = mutableListOf<PagePlan>()
        for (group in groups(candidates)) {
            if (out.size >= limit) break
            val title = group.map { pageTitle(it.name) }.firstOrNull { it.isNotEmpty() } ?: continue
            if (answered(title, pages)) continue
            val key = keyOf(title, layout.separator)
            if (key.isEmpty()) continue
            val path = pathOf(layout, key)
            val lower = path.lowercase()
            if (lower in taken || lower in planned) continue
            if (layout.ownFolder && folderTakenWithAnotherCase(path.substringBeforeLast('/') + "/", takenPaths)) continue
            planned += lower
            val aliases = group.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().filter { it != title }
            out += PagePlan(path, title, aliases, group.map { it.titleId }.distinct(), console)
        }
        return out
    }

    /**
     * The page's text: the vault's Other template when there is one ([template], its placeholders
     * filled, the game's keys set and everything else of it kept), else the same frontmatter built
     * here, then the title as the heading.
     */
    fun render(plan: PagePlan, template: String?, started: LocalDate?, today: LocalDate): NewGamePage {
        val title = plan.title
        val content = if (!template.isNullOrBlank()) {
            var text = TemplateEngine.apply(template, title, now = today.atStartOfDay())
            text = FrontmatterLines.set(text, "title", VaultSchema.doubleQuoted(title))
            text = withAliases(text, plan.aliases)
            text = FrontmatterLines.set(text, GamePages.TEMPLATE_KEY, OTHER_TEMPLATE)
            text = FrontmatterLines.set(text, GamePages.MATCH_KEY, GamePages.MATCH_EXACT)
            if (started != null) text = FrontmatterLines.set(text, "started", VaultSchema.singleQuoted(started.toString()))
            if (FrontmatterLines.get(text, GamePages.STATE_KEY).isNullOrBlank()) {
                text = FrontmatterLines.set(text, GamePages.STATE_KEY, GameState.BACKLOG.key)
            }
            text
        } else {
            val extra = buildList {
                add(GamePages.TEMPLATE_KEY to OTHER_TEMPLATE)
                add(GamePages.STATE_KEY to GameState.BACKLOG.key)
                add(GamePages.MATCH_KEY to GamePages.MATCH_EXACT)
                if (started != null) add("started" to VaultSchema.singleQuoted(started.toString()))
                add(GamePages.PLAYED_BEFORE_KEY to "''")
                add(GameMaps.KEY to "[]")
            }
            withAliases(VaultSchema.frontmatter(title = title, path = plan.path, date = today, extra = extra), plan.aliases) + "\n# $title\n"
        }
        return NewGamePage(plan, content)
    }

    private fun withAliases(content: String, aliases: List<String>): String =
        if (aliases.isEmpty()) content else FrontmatterLines.setList(content, "aliases", aliases.map(VaultSchema::doubleQuoted))

    /** Some page answers to [title], by its names (the prefix rule included), a tie between two pages too. */
    private fun answered(title: String, pages: List<GamePage>): Boolean {
        if (GameNames.pageForTitle(title, pages) != null) return true
        val name = GameNames.normalize(title)
        if (name.isEmpty()) return false
        return pages.any { page ->
            page.names.map(GameNames::normalize).any { it.isNotEmpty() && (name == it || (!page.exactName && name.startsWith("$it "))) }
        }
    }

    private fun folderTakenWithAnotherCase(folder: String, takenPaths: Collection<String>): Boolean {
        val lower = folder.lowercase()
        return takenPaths.any { it.lowercase().startsWith(lower) && !it.startsWith(folder) }
    }

    private fun pathOf(layout: GamePageLayout, key: String): String =
        (if (layout.ownFolder) listOf(layout.root, key, "$key.md") else listOf(layout.root, "$key.md"))
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private fun mostCommon(values: List<String>): String =
        values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

    /** The candidates as games: sharing an id (either way, or a sibling) or the same name; each and all most recent first. */
    private fun groups(candidates: List<PageCandidate>): List<List<PageCandidate>> {
        val parent = IntArray(candidates.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val names = candidates.map { GameNames.normalize(pageTitle(it.name)) }
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                val same = a.titleId in b.siblings || b.titleId in a.siblings || a.siblings.any { it in b.siblings } ||
                    (names[i].isNotEmpty() && names[i] == names[j])
                if (!same) continue
                val ra = root(i)
                val rb = root(j)
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            }
        }
        return candidates.indices.groupBy { root(it) }.values
            .map { members -> members.map { candidates[it] }.sortedWith(recentFirst) }
            .sortedWith { a, b -> recentFirst.compare(a.first(), b.first()) }
    }
}
