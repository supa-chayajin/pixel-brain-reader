package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffFormat
import java.time.LocalDate

/** Where a game stands (RFC-009 §5). The key is what the page's `game_state` holds: never rename one. */
enum class GameState(val key: String, val label: String, val group: GameGroup) {
    PLAYING("playing", "Playing", GameGroup.PLAYING),
    NEXT("next", "Next", GameGroup.NEXT),
    PAUSED("paused", "Paused", GameGroup.BACKLOG),
    BACKLOG("backlog", "Backlog", GameGroup.BACKLOG),
    DONE("done", "Done", GameGroup.DONE),
    HUNDRED("100", "100%", GameGroup.DONE);

    companion object {
        /** A missing or unknown value is the backlog; "100%" reads as [HUNDRED]. */
        fun fromKey(raw: String?): GameState {
            val key = raw?.trim()?.lowercase()?.removeSuffix("%") ?: return BACKLOG
            return entries.firstOrNull { it.key == key } ?: BACKLOG
        }
    }
}

/** The Games tab's four groups, in order (RFC-009 §4.1). */
enum class GameGroup(val label: String) { PLAYING("Playing"), NEXT("Next"), BACKLOG("Backlog"), DONE("Done") }

/**
 * The templates: the six of D5, then Platformer and Action-adventure (13.1, the owner's interview of
 * 2026-11-16). The key is the page's `game_template`; an unknown one (the vault's `other`) shows as
 * [OTHER]. Keys are persisted in the pages: never rename one.
 */
enum class GameTemplate(val key: String, val label: String) {
    SOULS_LIKE("souls-like", "Souls-like"),
    NIGHTREIGN("nightreign", "Nightreign"),
    ROGUELITE("roguelite", "Roguelite"),
    OPEN_WORLD("open-world", "Open world"),
    SURVIVAL_HORROR("survival-horror", "Survival horror"),
    LEGO("lego", "LEGO"),
    PLATFORMER("platformer", "Platformer"),
    ACTION_ADVENTURE("action-adventure", "Action-adventure"),
    OTHER("", "Other");

    companion object {
        fun fromKey(raw: String?): GameTemplate {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.key.isNotEmpty() && it.key == key } ?: OTHER
        }
    }
}

/**
 * One game's page in the vault: any note whose frontmatter has [GamePages.TEMPLATE_KEY], wherever it
 * lives (the owner's are in their area for games) except `99_System/`, where the templates are.
 * [key] is the file name without `.md`: what a Timeline line's marker names. [playedBefore] is the
 * play time the owner typed from the console (hours), counted under the sessions until PSN measures
 * the game itself.
 */
data class GamePage(
    val path: String,
    val title: String,
    val aliases: List<String>,
    val template: GameTemplate,
    val state: GameState,
    val started: LocalDate?,
    val finished: LocalDate?,
    val playedBefore: Double? = null
) {
    val key: String get() = path.substringAfterLast('/').removeSuffix(".md")
    val folder: String get() = path.substringBeforeLast('/', "")

    /** Every name the page answers to: its title, then its aliases. */
    val names: List<String> get() = (listOf(title) + aliases).filter { it.isNotBlank() }.distinct()
}

/** Reads and edits game pages (pure). */
object GamePages {

    const val TEMPLATE_KEY = "game_template"
    const val STATE_KEY = "game_state"
    const val PLAYED_BEFORE_KEY = "played_before"

    /** The vault's templates live here and carry [TEMPLATE_KEY] too: a template is never a game. */
    private const val TEMPLATES_ROOT = "99_System/"

    /** The page in [content], or null when it isn't a game page. */
    fun parse(path: String, content: String): GamePage? {
        if (!path.endsWith(".md") || path.startsWith(TEMPLATES_ROOT)) return null
        val template = FrontmatterLines.get(content, TEMPLATE_KEY) ?: return null
        return GamePage(
            path = path,
            title = titleOf(path, content),
            aliases = FrontmatterLines.getList(content, "aliases").map { it.trim() }.filter { it.isNotEmpty() },
            template = GameTemplate.fromKey(template),
            state = GameState.fromKey(FrontmatterLines.get(content, STATE_KEY)),
            started = date(FrontmatterLines.get(content, "started")),
            finished = date(FrontmatterLines.get(content, "finished")),
            playedBefore = hours(FrontmatterLines.get(content, PLAYED_BEFORE_KEY))
        )
    }

    /**
     * Hours as the owner types them: `228`, `228.5`, `228,5`, `228 h`, `228h30` or `228:30`; null
     * when blank, negative or anything else. Hand-written matching, no regex word boundaries.
     */
    fun hours(raw: String?): Double? {
        val text = raw?.trim()?.lowercase()?.replace(" ", "")?.removeSuffix("h") ?: return null
        if (text.isEmpty()) return null
        HOURS_MINUTES.matchEntire(text)?.let { m ->
            val minutes = m.groupValues[2].toInt()
            return if (minutes < 60) m.groupValues[1].toInt() + minutes / 60.0 else null
        }
        return DECIMAL.matchEntire(text)?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    private val HOURS_MINUTES = Regex("^(\\d{1,5})[h:](\\d{1,2})$")
    private val DECIMAL = Regex("^\\d{1,5}(?:[.,]\\d{1,2})?$")

    /**
     * [content] with [hours] written to `played_before` (whole hours without decimals, else one
     * decimal) and today in `date_updated`; null [hours] empties the key. Every other byte kept.
     */
    fun withPlayedBefore(content: String, hours: Double?, today: LocalDate): String {
        val value = when {
            hours == null -> "''"
            hours == Math.floor(hours) -> hours.toLong().toString()
            else -> String.format(java.util.Locale.ROOT, "%.1f", hours)
        }
        var next = FrontmatterLines.set(content, PLAYED_BEFORE_KEY, value)
        if (next != content && FrontmatterLines.get(next, "date_updated") != null) {
            next = FrontmatterLines.set(next, "date_updated", VaultSchema.singleQuoted(today.toString()))
        }
        return next
    }

    /** [content] with [state] written to `game_state` and today in `date_updated`; every other byte kept. */
    fun withState(content: String, state: GameState, today: LocalDate): String {
        var next = FrontmatterLines.set(content, STATE_KEY, state.key.let { if (it == "100") VaultSchema.singleQuoted(it) else it })
        if (FrontmatterLines.get(next, "date_updated") != null) {
            next = FrontmatterLines.set(next, "date_updated", VaultSchema.singleQuoted(today.toString()))
        }
        if (state == GameState.PLAYING && FrontmatterLines.get(next, "started").isNullOrBlank()) {
            next = FrontmatterLines.set(next, "started", VaultSchema.singleQuoted(today.toString()))
        }
        if ((state == GameState.DONE || state == GameState.HUNDRED) && FrontmatterLines.get(next, "finished").isNullOrBlank()) {
            next = FrontmatterLines.set(next, "finished", VaultSchema.singleQuoted(today.toString()))
        }
        return next
    }

    /**
     * The page's own text for the app's game page (RFC-009 §4.2): the note without its frontmatter,
     * its title, its checklists (tickable cards there) and the derived 🏆 section (the trophy card).
     * A heading left with nothing under it goes too. Blank when the page holds only checklists.
     */
    fun prose(content: String): String {
        val kept = mutableListOf<String>()
        var inTrophies = false
        var titleDropped = false
        bodyLines(content).forEach { raw ->
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) inTrophies = trimmed == TrophySection.HEADING
            if (inTrophies) return@forEach
            if (!titleDropped && trimmed.startsWith("# ")) {
                titleDropped = true
                return@forEach
            }
            if (CHECK_ITEM.matches(line)) return@forEach
            kept += line
        }
        val withoutEmptyHeadings = kept.filterIndexed { i, line ->
            val level = headingLevel(line) ?: return@filterIndexed true
            val section = kept.drop(i + 1).takeWhile { next -> headingLevel(next)?.let { it > level } ?: true }
            section.any { it.isNotBlank() && headingLevel(it) == null }
        }
        return withoutEmptyHeadings.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val CHECK_ITEM = Regex("^\\s*[-*+]\\s+\\[[ xX]\\]\\s+.*$")

    private fun headingLevel(line: String): Int? {
        val trimmed = line.trimStart()
        val hashes = trimmed.takeWhile { it == '#' }.length
        return hashes.takeIf { it in 1..6 && trimmed.length > hashes && trimmed[hashes] == ' ' }
    }

    private fun bodyLines(content: String): List<String> {
        val lines = content.split("\n")
        if (lines.firstOrNull()?.trimEnd('\r') != "---") return lines
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd('\r') == "---" } ?: return lines
        return lines.drop(close + 1)
    }

    /** What "Ask Claude" types in the hand-off composer (D6): a question about the game, the owner finishes it. */
    fun askText(page: GamePage): String = DeskHandoffFormat.typed(QUESTION, "${page.title} —") + " "

    /** The typed hand-off kind a question carries (French, like the vault: [DeskHandoffFormat.KINDS]). */
    private const val QUESTION = "question"

    /** The library in the tab's groups: paused games first in Backlog, then by title. */
    fun grouped(pages: List<GamePage>): Map<GameGroup, List<GamePage>> =
        GameGroup.entries.associateWith { group ->
            pages.filter { it.state.group == group }
                .sortedWith(compareBy<GamePage>({ it.state != GameState.PAUSED }, { it.title.lowercase() }))
        }

    /** The frontmatter title, else the first `# ` heading, else the file name with spaces. */
    private fun titleOf(path: String, content: String): String {
        FrontmatterLines.get(content, "title")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        content.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return path.substringAfterLast('/').removeSuffix(".md").replace('_', ' ')
    }

    private fun date(raw: String?): LocalDate? = raw?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
