package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlock
import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Snippets & runbooks (E4): every code block of the vault, the runbooks, and their incident logs. */
@Singleton
class SnippetRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val modesRepository: ModesRepository,
    private val draftWriter: DraftWriter
) {

    /** Every visible note's code blocks, with the note's title. */
    suspend fun codeBlocks(): List<Pair<CodeBlock, String>> = withContext(Dispatchers.IO) {
        val visibility = modesRepository.currentVisibility()
        fileContentDao.contentsUnder("")
            .filter { it.path.endsWith(".md") && visibility.isVisible(it.path) }
            .flatMap { row ->
                val title = WorkNote.from(row.path, row.content).title
                CodeBlocks.extract(row.path, row.content).map { it to title }
            }
    }

    suspend fun runbooks(): List<WorkNote> = withContext(Dispatchers.IO) {
        val visibility = modesRepository.currentVisibility()
        fileContentDao.contentsUnder("")
            .filter { it.path.endsWith(".md") && visibility.isVisible(it.path) }
            .map { WorkNote.from(it.path, it.content) }
            .filter(Runbooks::isRunbook)
            .sortedBy { it.title }
    }

    suspend fun runbook(path: String): Pair<WorkNote, List<RunbookStep>>? = withContext(Dispatchers.IO) {
        val content = fileContentDao.getContent(path) ?: return@withContext null
        val note = WorkNote.from(path, content)
        note to Runbooks.steps(path, content)
    }

    /** Writes the run's incident log to the inbox; returns its path. */
    suspend fun writeIncident(runbook: WorkNote, startedAt: LocalDateTime, results: List<StepResult>, notes: String?): String =
        withContext(Dispatchers.IO) {
            draftWriter.write(
                Runbooks.incidentPath(runbook, startedAt),
                Runbooks.incidentLog(runbook, startedAt, results, LocalDateTime.now().withSecond(0).withNano(0), notes, LocalDate.now())
            )
        }
}
