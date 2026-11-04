package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.LinkDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project dashboard (C11): one summary per `20_Projects/<folder>`, read from the vault mirror
 * (`file_contents`) and the links index — never the disk, never written. Projects in a work folder
 * are left out while work is hidden (Modes).
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val linkDao: LinkDao,
    private val modesRepository: ModesRepository
) {

    /** Every visible project, the most recently active first. */
    suspend fun projects(today: LocalDate = LocalDate.now()): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val visibility = modesRepository.visibility.value
        val byRoot = fileContentDao.contentsUnder("${ProjectFormat.PROJECTS_FOLDER}/")
            .mapNotNull { pc -> ProjectFormat.rootOf(pc.path)?.let { it to pc } }
            .filter { (root, _) -> visibility.isVisible(root) }
            .groupBy({ it.first }, { it.second.path to it.second.content })
        if (byRoot.isEmpty()) return@withContext emptyList()
        val links = linkDao.allNoteLinks().map { it.source to it.targetKey }
        byRoot.map { (root, notes) -> ProjectFormat.summarize(root, notes.toMap(), links, today) }
            .sortedWith(compareByDescending<ProjectSummary> { it.lastActivity }.thenBy { it.root })
    }

    suspend fun project(root: String, today: LocalDate = LocalDate.now()): ProjectSummary? =
        projects(today).firstOrNull { it.root == root }
}
