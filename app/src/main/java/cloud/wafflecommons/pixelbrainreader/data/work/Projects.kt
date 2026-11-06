package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import java.time.LocalDate

/** One day of a project's auto-generated worklogs (every file of that date summed). */
data class WorklogDay(
    val date: LocalDate,
    val commits: Int,
    val sessions: Int,
    val prompts: Int,
    val tokens: Long,
    val subjects: List<String>
)

/** An open checkbox found in one of the project's notes; [section] is its `##` heading in the project TODO. */
data class ProjectAction(
    val path: String,
    val text: String,
    val due: LocalDate?,
    val mine: Boolean,
    val section: String? = null
)

/** Everything the project page shows, computed from the vault mirror only. */
data class ProjectSummary(
    val root: String,
    val title: String,
    val status: String?,
    /** The most recent day with a worklog, or the most recent note update. */
    val lastActivity: LocalDate?,
    /** The last [ProjectFormat.WINDOW_DAYS] days that have a worklog, newest first. */
    val days: List<WorklogDay>,
    /** Your open checkboxes: the notes' and the project TODO's `@moi` lines, yours first, then by due date. */
    val actions: List<ProjectAction>,
    /** The project TODO's other open lines, in file order: what the desktop sessions do on your go. */
    val claudeQueue: List<ProjectAction>,
    /** README, roadmap, RFC, TODO and portfolio notes, plus the index of each desktop mirror. */
    val keyNotes: List<WorkNote>,
    /** Hand-edited notes (never a desktop mirror), most recently updated first. */
    val recentNotes: List<WorkNote>,
    /** Notes outside the project that link into it. */
    val linkedFrom: List<String>,
    /**
     * The project's `TODO.md`, when it has one: the live list kept in the vault, or the desktop's
     * copy of a repository's punch list (a mirror: linked, never parsed).
     */
    val todoNote: WorkNote?
) {
    val name: String get() = root.substringAfterLast('/')
    val commits: Int get() = days.sumOf { it.commits }
    val sessions: Int get() = days.sumOf { it.sessions }
    val tokens: Long get() = days.sumOf { it.tokens }

    /** "Where you left off": the newest worklog day's first commit subjects. */
    val leftOff: WorklogDay? get() = days.firstOrNull { it.subjects.isNotEmpty() } ?: days.firstOrNull()
    val nextAction: ProjectAction? get() = actions.firstOrNull()
}

/**
 * Projects are the `20_Projects/<folder>` directories of the vault, discovered from the notes
 * (never a hard-coded list: the repository is public and the folders are the user's own).
 * Worklogs (`…/Worklog/yyyy-MM-dd.md`, auto-generated on the desktops) are read-only inputs.
 */
object ProjectFormat {
    const val PROJECTS_FOLDER = "20_Projects"
    const val WINDOW_DAYS = 14L

    private val worklogName = Regex("""\d{4}-\d{2}-\d{2}\.md""")
    private val sessionsLine = Regex("""([\d,]+) session\(s\) · ([\d,]+) prompt\(s\)""")
    private val tokensLine = Regex("""([\d,]+) tokens total""")

    /** `20_Projects/<folder>` for a path inside a project, else null. */
    fun rootOf(path: String): String? {
        if (!path.startsWith("$PROJECTS_FOLDER/")) return null
        val folder = path.removePrefix("$PROJECTS_FOLDER/").substringBefore('/', missingDelimiterValue = "")
        // A file directly in 20_Projects/ is not a project.
        if (folder.isEmpty() || !path.removePrefix("$PROJECTS_FOLDER/$folder").startsWith("/")) return null
        return "$PROJECTS_FOLDER/$folder"
    }

    fun isWorklog(path: String): Boolean =
        path.contains("/Worklog/") && worklogName.matches(path.substringAfterLast('/'))

    fun worklogDate(path: String): LocalDate? =
        if (isWorklog(path)) runCatching { LocalDate.parse(path.substringAfterLast('/').removeSuffix(".md")) }.getOrNull() else null

    /**
     * One worklog file. Commits are the bullets under a `## 🧬 Commits` heading (a workspace
     * roll-up has none, so per-app files are never counted twice); sessions, prompts and tokens
     * come from the `## 🤖 AI sessions` lines (only in the roll-up in per-app mode).
     */
    fun parseWorklog(date: LocalDate, content: String): WorklogDay {
        val subjects = mutableListOf<String>()
        var inCommits = false
        for (line in content.lineSequence()) {
            if (line.startsWith("## ")) {
                inCommits = line.removePrefix("## ").trimStart().let { it.startsWith("🧬 Commits") || it.startsWith("Commits") }
                continue
            }
            if (inCommits && line.startsWith("- ")) subjects += line.removePrefix("- ").trim()
        }
        val sessions = sessionsLine.find(content)
        val tokens = tokensLine.find(content)
        return WorklogDay(
            date = date,
            commits = subjects.size,
            sessions = sessions?.groupValues?.get(1)?.number()?.toInt() ?: 0,
            prompts = sessions?.groupValues?.get(2)?.number()?.toInt() ?: 0,
            tokens = tokens?.groupValues?.get(1)?.number() ?: 0L,
            subjects = subjects
        )
    }

    /** Sums the files of one date (a per-app project writes several). */
    fun mergeDays(days: List<WorklogDay>): List<WorklogDay> =
        days.groupBy { it.date }.map { (date, same) ->
            WorklogDay(
                date = date,
                commits = same.sumOf { it.commits },
                sessions = same.sumOf { it.sessions },
                prompts = same.sumOf { it.prompts },
                tokens = same.sumOf { it.tokens },
                subjects = same.flatMap { it.subjects }
            )
        }.sortedByDescending { it.date }

    fun isKeyNote(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name == "readme.md" || name == "todo.md" || name.startsWith("_portfolio") ||
            name.contains("roadmap") || name.startsWith("rfc") || path.lowercase().contains("/rfcs/")
    }

    /**
     * Builds one project's page from its notes ([notes]: path → content, all under [root]),
     * the vault's links (source → target key) and the user's meeting actions.
     */
    fun summarize(
        root: String,
        notes: Map<String, String>,
        links: List<Pair<String, String>>,
        today: LocalDate
    ): ProjectSummary {
        val from = today.minusDays(WINDOW_DAYS - 1)
        val worklogs = notes.mapNotNull { (path, content) ->
            worklogDate(path)?.let { d -> parseWorklog(d, content) }
        }
        val allDays = mergeDays(worklogs)
        val days = allDays.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
        // Hand-offs have their own card (C13): not a generic note or next action.
        val handNotes = notes.filterKeys { !isWorklog(it) && !DeskHandoffFormat.isHandoff(it) }.map { (p, c) -> WorkNote.from(p, c) }
        val readme = handNotes.firstOrNull { it.path.equals("$root/README.md", ignoreCase = true) }
        val todo = handNotes.firstOrNull { it.path.equals("$root/TODO.md", ignoreCase = true) }
        // Desktop mirrors are rewritten every session: their checkboxes are the source
        // repository's, not your actions, and their dozens of roadmaps and RFCs would bury the
        // notes you wrote. Only the mirror's index stays, as one key note.
        val (mirrors, ownNotes) = handNotes.partition { it.isMirror }
        // The live TODO splits in two: `@moi` lines are yours, the others are Claude's queue. A
        // mirrored TODO is a repository's punch list: linked, never parsed (settled decision #15).
        val (todoMine, claudeQueue) = todo?.takeIf { !it.isMirror }
            ?.let { todoActions(it.path, it.content) }.orEmpty()
            .partition { it.mine }

        val actions = (
            ownNotes
                .filter { it != todo }
                .flatMap { note ->
                    MeetingFormat.actions(note.path, note.content).filter { !it.done }.map {
                        ProjectAction(note.path, plainText(it.text), it.due, it.mine)
                    }
                } + todoMine
            )
            // Yours first, then the ones with a due date (soonest first), then the rest.
            .sortedWith(compareBy<ProjectAction>({ !it.mine }, { it.due == null }, { it.due }))

        val keys = handNotes.map { LinkExtractor.keyOfPath(it.path) }.toSet()
        val linkedFrom = links
            .filter { (source, key) -> key in keys && rootOf(source) != root }
            .map { it.first }
            .distinct()
            .sorted()

        val lastNoteUpdate = handNotes.mapNotNull { it.updated }.filter { !it.isAfter(today) }.maxOrNull()
        val lastWorklog = allDays.firstOrNull { !it.date.isAfter(today) }?.date
        return ProjectSummary(
            root = root,
            title = readme?.title?.let(::cleanTitle) ?: root.substringAfterLast('/'),
            status = readme?.status,
            lastActivity = listOfNotNull(lastNoteUpdate, lastWorklog).maxOrNull(),
            days = days,
            actions = actions,
            claudeQueue = claudeQueue,
            keyNotes = (ownNotes.filter { isKeyNote(it.path) && it != readme } + mirrors.filter { MirrorNotes.isMirrorIndex(it.content) })
                .sortedBy { it.path },
            recentNotes = ownNotes
                .filter { !isKeyNote(it.path) && it.updated != null }
                .sortedByDescending { it.updated }
                .take(6),
            linkedFrom = linkedFrom,
            todoNote = todo
        )
    }

    /**
     * The open lines of a live project TODO (kept in the vault by the desktop sessions and you),
     * each with the `##` heading it sits under. `@moi` marks yours; the TODO's own rule is that
     * every other line is Claude's, done when you give the go. Fenced code is skipped.
     */
    fun todoActions(path: String, content: String): List<ProjectAction> {
        val result = mutableListOf<ProjectAction>()
        var section: String? = null
        var inFence = false
        for (line in content.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            when {
                line.startsWith("## ") -> section = line.removePrefix("## ").trim().ifEmpty { null }
                line.startsWith("# ") -> section = null
                else -> MeetingFormat.actions(path, line).firstOrNull()?.takeIf { !it.done }?.let { action ->
                    // Yours drop the @moi marker; Claude's keep any @name they mention.
                    val text = if (action.mine) action.text else ObjectiveFormat.milestones(line).first().text
                    result += ProjectAction(path, plainText(text), action.due, action.mine, section)
                }
            }
        }
        return result
    }

    /** "📌 21_X — Project Log (read me)" → "21_X". */
    internal fun cleanTitle(title: String): String =
        title.substringBefore(" — ").trimStart { !it.isLetterOrDigit() }.trim().ifEmpty { title }

    /** Markdown out of a one-line action: `**b**`, `_i_`, `` `code` ``, `[[note|alias]]` → plain words. */
    internal fun plainText(text: String): String {
        var t = text.replace("**", "").replace("__", "").replace("`", "")
        t = Regex("""\[\[([^\]|]+)\|([^\]]+)]]""").replace(t) { it.groupValues[2] }
        t = Regex("""\[\[([^\]]+)]]""").replace(t) { it.groupValues[1].substringAfterLast('/') }
        t = Regex("""\[([^\]]+)]\([^)]*\)""").replace(t) { it.groupValues[1] }
        return t.trim()
    }

    private fun String.number(): Long? = replace(",", "").toLongOrNull()
}
