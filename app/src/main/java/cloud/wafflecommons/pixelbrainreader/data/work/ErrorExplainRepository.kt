package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** An error read and looked up (C8): the notes that met it, and the project it belongs to, if any. */
data class ErrorLookupResult(
    val raw: String,
    val trace: ErrorTrace,
    val occurrences: List<ErrorLookup.Occurrence>,
    val projectRoot: String?
)

/**
 * C8 "Explain this error": finds where the vault met an error before (full-text first, its words;
 * then vectors, its meaning), asks the on-device model to explain it, and writes the bug note.
 * Everything runs on the phone; the current mode's visibility and the private-notes switch apply.
 */
@Singleton
class ErrorExplainRepository @Inject constructor(
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val vectorSearchEngine: VectorSearchEngine,
    private val fileContentDao: FileContentDao,
    private val projectRepository: ProjectRepository,
    private val modesRepository: ModesRepository,
    private val localAiManager: LocalAiManager,
    private val fileRepository: FileRepository,
    private val draftWriter: DraftWriter
) {
    private val pending = MutableStateFlow<String?>(null)

    /** The share sheet hands an error over here; the screen takes it once. */
    fun offer(text: String) {
        pending.value = text
    }

    fun takePending(): String? = pending.getAndUpdate { null }

    /** Null when [raw] does not read as an error. */
    suspend fun lookup(raw: String): ErrorLookupResult? = withContext(Dispatchers.IO) {
        val trace = ErrorTrace.parse(raw) ?: return@withContext null
        val visibility = modesRepository.currentVisibility()
        val textLists = trace.queries().map { query ->
            vaultDiscoveryRepository.fullTextHits(query, limit = TEXT_LIMIT)
                .filter { visibility.isVisible(it.path) }
                .map { ErrorLookup.Hit(it.path, it.excerpt) }
        }
        val vectorHits = try {
            vectorSearchEngine.search(trace.headline, limit = VECTOR_LIMIT).map { ErrorLookup.Hit(it.fileId, it.content.take(EXCERPT_CHARS)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // no embeddings yet (or a read-only device): the error's words alone
        }
        val occurrences = ErrorLookup.merge(textLists, vectorHits).map { it.copy(isRunbook = isRunbook(it.path)) }
        val roots = try {
            projectRepository.projects().map { it.root }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val projectRoot = occurrences.firstNotNullOfOrNull { ProjectFormat.projectFor(roots, it.path) }
            ?: ErrorLookup.projectNamed(roots, raw)
        ErrorLookupResult(raw, trace, occurrences, projectRoot)
    }

    /** Gemini Nano's explanation; [generalKnowledge] when no note matched and the owner asked anyway. */
    suspend fun explain(result: ErrorLookupResult, generalKnowledge: Boolean): Result<String> =
        localAiManager.generateResponse(ErrorLookup.prompt(result.trace, result.raw, result.occurrences, generalKnowledge))

    /** Writes the bug note (never over another one) and returns its path. */
    suspend fun writeBugNote(result: ErrorLookupResult, explanation: String?, generalKnowledge: Boolean): String = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val path = BugNote.path(BugNote.folder(result.projectRoot), now, result.trace)
        val template = fileRepository.readFile(BugNote.TEMPLATE_PATH)
        draftWriter.write(path, BugNote.build(template, path, result.trace, result.raw, explanation, generalKnowledge, result.occurrences, now))
    }

    private suspend fun isRunbook(path: String): Boolean {
        val content = fileContentDao.getContent(path) ?: return false
        return Runbooks.isRunbook(WorkNote.from(path, content))
    }

    private companion object {
        const val TEXT_LIMIT = 8
        const val VECTOR_LIMIT = 6
        const val EXCERPT_CHARS = 400
    }
}
