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

/** The six templates (D5). The key is the page's `game_template`; an unknown one shows as [OTHER]. */
enum class GameTemplate(val key: String, val label: String) {
    SOULS_LIKE("souls-like", "Souls-like"),
    NIGHTREIGN("nightreign", "Nightreign"),
    ROGUELITE("roguelite", "Roguelite"),
    OPEN_WORLD("open-world", "Open world"),
    SURVIVAL_HORROR("survival-horror", "Survival horror"),
    LEGO("lego", "LEGO"),
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
 * lives (the owner's are in their area for games). [key] is the file name without `.md`: what a
 * Timeline line's marker names.
 */
data class GamePage(
    val path: String,
    val title: String,
    val aliases: List<String>,
    val template: GameTemplate,
    val state: GameState,
    val started: LocalDate?,
    val finished: LocalDate?
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

    /** The page in [content], or null when it isn't a game page. */
    fun parse(path: String, content: String): GamePage? {
        if (!path.endsWith(".md")) return null
        val template = FrontmatterLines.get(content, TEMPLATE_KEY) ?: return null
        return GamePage(
            path = path,
            title = titleOf(path, content),
            aliases = FrontmatterLines.getList(content, "aliases").map { it.trim() }.filter { it.isNotEmpty() },
            template = GameTemplate.fromKey(template),
            state = GameState.fromKey(FrontmatterLines.get(content, STATE_KEY)),
            started = date(FrontmatterLines.get(content, "started")),
            finished = date(FrontmatterLines.get(content, "finished"))
        )
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
