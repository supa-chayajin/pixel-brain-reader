package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.LinkDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project dashboard (C11): one summary per `20_Projects/<folder>`, read from the vault mirror
 * (`file_contents`) and the links index — never the disk, never written. Projects in a work folder
 * are left out while work is hidden (Modes).
 *
 * Summaries are cached and rebuilt only when the project notes, the links or the day change: the
 * screen and the Projects widget (re-rendered after every mood log or habit tick) share the cache.
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val linkDao: LinkDao,
    private val modesRepository: ModesRepository
) {
    private val mutex = Mutex()
    private var cacheKey: String? = null
    private var cache: List<ProjectSummary> = emptyList()

    /** Every visible project, the most recently active first. */
    suspend fun projects(today: LocalDate = LocalDate.now()): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val visibility = modesRepository.visibility.value
        all(today).filter { visibility.isVisible(it.root) }
    }

    /**
     * The projects the mode's project screens show (the Projects tab, Daily's block, the widget):
     * Off none, Perso the ticked ones, Work every visible one ([Visibility.showsProject]). Workers and
     * widgets pass the policy read from disk.
     */
    suspend fun shownProjects(
        visibility: Visibility = modesRepository.visibility.value,
        today: LocalDate = LocalDate.now()
    ): List<ProjectSummary> = withContext(Dispatchers.IO) {
        all(today).filter { visibility.showsProject(it.root) }
    }

    /** Every project, whatever the mode (Modes settings' Perso checklist). */
    suspend fun everyProject(today: LocalDate = LocalDate.now()): List<ProjectSummary> = withContext(Dispatchers.IO) { all(today) }

    /** What each visible project did between [start] and [end], most commits first (the evidence report). */
    suspend fun periodActivity(start: LocalDate, end: LocalDate): List<ProjectPeriodActivity> = withContext(Dispatchers.IO) {
        val visibility = modesRepository.visibility.value
        ProjectFormat.group(fileContentDao.contentsUnder(PREFIX).map { it.path to it.content })
            .filterKeys { visibility.isVisible(it) }
            .mapNotNull { (root, notes) -> ProjectFormat.periodActivity(root, notes, start, end) }
            .sortedWith(compareByDescending<ProjectPeriodActivity> { it.commits }.thenBy { it.root })
    }

    /** One visible project, or null when it is hidden or gone. */
    suspend fun project(root: String, today: LocalDate = LocalDate.now()): ProjectSummary? =
        projects(today).firstOrNull { it.root == root }

    /** Every project, visible or not, from the cache when nothing changed. */
    private suspend fun all(today: LocalDate): List<ProjectSummary> = mutex.withLock {
        val key = listOf(
            fileContentDao.signatureUnder(PREFIX),
            linkDao.signature(),
            today.toString()
        ).joinToString("|")
        if (key == cacheKey) return@withLock cache

        // Sub-projects (a folder with its own Handoff.md, e.g. an app of a workspace) are their own projects.
        val byRoot = ProjectFormat.group(fileContentDao.contentsUnder(PREFIX).map { it.path to it.content })
        val links = if (byRoot.isEmpty()) emptyList() else linkDao.allNoteLinks().map { it.source to it.targetKey }
        cache = byRoot.map { (root, notes) -> ProjectFormat.summarize(root, notes, links, today) }
            .sortedWith(compareByDescending<ProjectSummary> { it.lastActivity }.thenBy { it.root })
        cacheKey = key
        cache
    }

    private companion object {
        const val PREFIX = "${ProjectFormat.PROJECTS_FOLDER}/"
    }
}
