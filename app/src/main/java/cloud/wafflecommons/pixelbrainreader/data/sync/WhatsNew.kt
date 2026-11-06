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

    /** A project's live list: its `TODO.md`, or its `Next_Actions.md` when the TODO is a repository's copy. */
    private val LIVE_LISTS = setOf("todo.md", "next_actions.md")

    private fun isLiveList(path: String, root: String) =
        path.substringBeforeLast('/') == root && path.substringAfterLast('/').lowercase() in LIVE_LISTS

    /** The changed files whose before/after contents [of] needs: each project's live list and hand-offs. */
    fun needsContent(path: String): Boolean {
        val root = ProjectFormat.rootOf(path) ?: return false
        return isLiveList(path, root) || path == DeskHandoffFormat.path(root)
    }

    fun isReport(path: String): Boolean = ProjectFormat.rootOf(path) != null && path.contains(REPORTS) && path.endsWith(".md")

    fun of(changes: List<ChangedPath>, before: Map<String, String?>, after: Map<String, String?>): List<ProjectNews> {
        val news = mutableMapOf<String, ProjectNews>()
        fun update(root: String, change: (ProjectNews) -> ProjectNews) {
            news[root] = change(news[root] ?: ProjectNews(root))
        }
        for (c in changes) {
            val root = ProjectFormat.rootOf(c.path) ?: continue
            when {
                isReport(c.path) && (c.change == "add" || c.change == "rename") ->
                    update(root) { it.copy(reports = it.reports + c.path) }

                isLiveList(c.path, root) && c.change != "delete" -> {
                    val now = after[c.path]?.takeIf { !MirrorNotes.isMirror(it) } ?: continue
                    val known = before[c.path]?.takeIf { !MirrorNotes.isMirror(it) }
                        ?.let { ProjectFormat.todoActions(c.path, it) }.orEmpty()
                        .filter { it.mine }.map { it.text }.toSet()
                    val fresh = ProjectFormat.todoActions(c.path, now).filter { it.mine && it.text !in known }.map { it.text }
                    if (fresh.isNotEmpty()) update(root) { it.copy(myLines = it.myLines + fresh) }
                }

                c.path == DeskHandoffFormat.path(root) && c.change == "modify" -> {
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
