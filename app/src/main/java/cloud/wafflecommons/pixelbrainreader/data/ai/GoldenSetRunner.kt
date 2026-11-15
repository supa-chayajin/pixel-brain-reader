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
 * vector search, through ranked full-text search and through their fusion (R2, 12.20), with every
 * folder visible (the retriever is measured, not the mode) and the golden-set note itself never
 * counted. Read-only.
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
            // One search of each kind, at the hybrid's candidate count. The meaning and words columns
            // keep their first GoldenSet.K + 3 results, as before 12.20, so their numbers stay
            // comparable; the hybrid column is the production fusion over all the candidates (R2).
            val meaningHits = vectorSearchEngine.search(q.question, limit = HybridRanking.CANDIDATES, ignoreVisibility = true)
            val wordPaths = vaultDiscoveryRepository.fullTextHits(q.question, limit = HybridRanking.CANDIDATES, anyTerm = true)
                .map { it.path }
            val byMeaning = meaningHits.take(GoldenSet.K + 3).map { it.fileId }.distinct().filter { it != GoldenSet.PATH }
            val byWords = wordPaths.take(GoldenSet.K + 3).filter { it != GoldenSet.PATH }
            val byHybrid = HybridRanking.fuse(meaningHits, { it.fileId }, wordPaths)
                .map { it.fileId }.distinct().filter { it != GoldenSet.PATH }
            Log.i(TAG, "${q.id} [${q.kind}/${q.language}] meaning=${byMeaning.take(GoldenSet.K)} words=${byWords.take(GoldenSet.K)} hybrid=${byHybrid.take(GoldenSet.K)} expected=${q.expected}")
            GoldenSet.Outcome(q, byMeaning, byWords, byHybrid)
        }
        GoldenSet.score(outcomes).also { Log.i(TAG, it.summary()) }
    }

    private companion object {
        const val TAG = "GOLDEN"
    }
}
