package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passages a grounded answer reads. With hybrid search on (R2, 12.20, on by default: the
 * owner's D3), the meaning search's top [HybridRanking.CANDIDATES] re-ranked by the words they
 * share with the question ([HybridRanking]); off, the meaning search alone, as before 12.20.
 */
@Singleton
class NoteRetrieval @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val userPreferences: UserPreferencesRepository
) {
    suspend fun search(query: String, limit: Int): List<SearchHit> {
        if (!userPreferences.hybridSearch.first()) return vectorSearchEngine.search(query, limit = limit)
        val byMeaning = vectorSearchEngine.search(query, limit = maxOf(limit, HybridRanking.CANDIDATES))
        // Nothing above the floor: "not in your notes" (settled decision #2), whatever the words say.
        if (byMeaning.isEmpty()) return emptyList()
        return HybridRanking.fuse(byMeaning, { it.fileId }, wordRanks(query)).take(limit)
    }

    /** Full-text search's notes for [query], best first; a failure leaves the meaning order alone. */
    suspend fun wordRanks(query: String): List<String> = try {
        vaultDiscoveryRepository.fullTextHits(query, limit = HybridRanking.CANDIDATES, anyTerm = true).map { it.path }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
