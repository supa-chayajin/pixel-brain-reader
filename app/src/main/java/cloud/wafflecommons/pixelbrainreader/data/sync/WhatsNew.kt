package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.remote.ChangedPath
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectFormat
import kotlinx.serialization.Serializable

/** What a pull brought one project that is about you (the sync digest's "for you"). */
@Serializable
data class ProjectNews(
    val root: String,
    /** New or changed `@moi` lines of the project's live TODO, as they now read. */
    val myLines: List<String> = emptyList(),
    /** New Claude reports: notes added under `<project>/Claude/Reports/`. */
    val reports: List<String> = emptyList(),
    /** Your hand-offs the desktop ticked: picked up. */
    val pickedUp: List<String> = emptyList()
) {
    val name: String get() = root.substringAfterLast('/')
    val isEmpty: Boolean get() = myLines.isEmpty() && reports.isEmpty() && pickedUp.isEmpty()
}

/**
 * From what a pull changed (C4): per project, only what concerns you — `@moi` lines added or
 * reworded in the live TODO (a mirrored TODO is never read), new Claude reports, and hand-offs
 * ticked on the desktop. The contents come from the commits before and after the pull, so a line
 * you ticked or wrote on the phone is never news. Pure: the caller fetches the contents.
 */
object WhatsNew {

    private const val REPORTS = "/Claude/Reports/"

    private val LIVE_LISTS = setOf("todo.md", "next_actions.md")

    /**
     * The cockpit a file belongs to: its project `20_Projects/<project>`, or one of [subRoots]
     * (`20_Projects/<project>/<app>`, a folder with its own Handoff.md), the file sitting directly in it.
     */
    private fun cockpitOf(path: String, subRoots: Set<String>): String? {
        val parts = path.split('/')
        if (parts.firstOrNull() != ProjectFormat.PROJECTS_FOLDER) return null
        val folder = parts.dropLast(1).joinToString("/")
        return when (parts.size) {
            3 -> folder
            4 -> folder.takeIf { it in subRoots }
            else -> null
        }
    }

    private fun isLiveList(path: String) = path.substringAfterLast('/').lowercase() in LIVE_LISTS

    /** The folder a sub-project would be, for a file inside it (`20_Projects/<p>/<app>/…`). */
    fun subProjectCandidate(path: String): String? =
        path.split('/').takeIf { it.size >= 4 && it[0] == ProjectFormat.PROJECTS_FOLDER }?.take(3)?.joinToString("/")

    /** The changed files whose before/after contents [of] needs: each cockpit's live list and hand-offs. */
    fun needsContent(path: String, subRoots: Set<String> = emptySet()): Boolean =
        cockpitOf(path, subRoots) != null && (isLiveList(path) || path.endsWith("/" + DeskHandoffFormat.FILE_NAME))

    /**
     * The cockpit whose own `Claude/Reports/` holds [path] — a project, or one of [subRoots] — as
     * on the project page. A `Claude/Reports/` deeper down (inside a mirrored repository) is not one.
     */
    private fun reportRoot(path: String, subRoots: Set<String>): String? {
        if (!path.endsWith(".md") || !path.contains(REPORTS)) return null
        val root = path.substringBefore(REPORTS)
        return root.takeIf { it == ProjectFormat.rootOf(path) || it in subRoots }
    }

    fun isReport(path: String, subRoots: Set<String> = emptySet()): Boolean = reportRoot(path, subRoots) != null

    fun of(
        changes: List<ChangedPath>,
        before: Map<String, String?>,
        after: Map<String, String?>,
        subRoots: Set<String> = emptySet()
    ): List<ProjectNews> {
        val news = mutableMapOf<String, ProjectNews>()
        fun update(root: String, change: (ProjectNews) -> ProjectNews) {
            news[root] = change(news[root] ?: ProjectNews(root))
        }
        for (c in changes) {
            when {
                isReport(c.path, subRoots) && (c.change == "add" || c.change == "rename") ->
                    update(c.path.substringBefore(REPORTS)) { it.copy(reports = it.reports + c.path) }

                isLiveList(c.path) && c.change != "delete" -> {
                    val root = cockpitOf(c.path, subRoots) ?: continue
                    val now = after[c.path]?.takeIf { !MirrorNotes.isMirror(it) } ?: continue
                    val known = before[c.path]?.takeIf { !MirrorNotes.isMirror(it) }
                        ?.let { ProjectFormat.todoActions(c.path, it) }.orEmpty()
                        .filter { it.mine }.map { it.text }.toSet()
                    val fresh = ProjectFormat.todoActions(c.path, now).filter { it.mine && it.text !in known }.map { it.text }
                    if (fresh.isNotEmpty()) update(root) { it.copy(myLines = it.myLines + fresh) }
                }

                c.path.endsWith("/" + DeskHandoffFormat.FILE_NAME) && c.change == "modify" -> {
                    val root = cockpitOf(c.path, subRoots) ?: continue
                    val open = before[c.path]?.let(DeskHandoffFormat::items).orEmpty().filter { !it.done }
                    val ticked = after[c.path]?.let(DeskHandoffFormat::items).orEmpty()
                        .filter { done -> done.done && open.any { it.text == done.text && it.at == done.at } }
                        .map { it.text }
                    if (ticked.isNotEmpty()) update(root) { it.copy(pickedUp = it.pickedUp + ticked) }
                }
            }
        }
        return news.values.filter { !it.isEmpty }.sortedBy { it.root }
    }
}
