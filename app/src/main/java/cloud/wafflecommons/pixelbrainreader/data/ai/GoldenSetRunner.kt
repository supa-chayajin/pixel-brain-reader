package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The retrieval harness (R1): every question of the vault's golden set through the production
 * vector search and through ranked full-text search, with every folder visible (the retriever is
 * measured, not the mode) and the golden-set note itself never counted. Read-only.
 */
@Singleton
class GoldenSetRunner @Inject constructor(
    private val fileRepository: FileRepository,
    private val vectorSearchEngine: VectorSearchEngine,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository
) {
    /** Null when the golden-set note is missing or holds no question. */
    suspend fun run(): GoldenSet.Report? = withContext(Dispatchers.IO) {
        val content = fileRepository.readFile(GoldenSet.PATH) ?: return@withContext null
        val questions = GoldenSet.parse(content)
        if (questions.isEmpty()) return@withContext null
        val outcomes = questions.map { q ->
            val byMeaning = vectorSearchEngine.search(q.question, limit = GoldenSet.K + 3, ignoreVisibility = true)
                .map { it.fileId }.distinct().filter { it != GoldenSet.PATH }
            val byWords = vaultDiscoveryRepository.fullTextHits(q.question, limit = GoldenSet.K + 3, anyTerm = true)
                .map { it.path }.filter { it != GoldenSet.PATH }
            Log.i(TAG, "${q.id} [${q.kind}/${q.language}] meaning=${byMeaning.take(GoldenSet.K)} words=${byWords.take(GoldenSet.K)} expected=${q.expected}")
            GoldenSet.Outcome(q, byMeaning, byWords)
        }
        GoldenSet.score(outcomes).also { Log.i(TAG, it.summary()) }
    }

    private companion object {
        const val TAG = "GOLDEN"
    }
}
