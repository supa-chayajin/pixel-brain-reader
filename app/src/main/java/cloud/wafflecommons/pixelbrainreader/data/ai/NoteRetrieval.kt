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
    /** [scope], when given, keeps only its notes (K2, 12.20): a folder's, or one note's passages. */
    suspend fun search(query: String, limit: Int, scope: ChatScope? = null): List<SearchHit> {
        val within: ((String) -> Boolean)? = scope?.let { it::contains }
        if (!userPreferences.hybridSearch.first()) return vectorSearchEngine.search(query, limit = limit, within = within)
        val byMeaning = vectorSearchEngine.search(query, limit = maxOf(limit, HybridRanking.CANDIDATES), within = within)
        // Nothing above the floor: "not in your notes" (settled decision #2), whatever the words say.
        if (byMeaning.isEmpty()) return emptyList()
        return HybridRanking.fuse(byMeaning, { it.fileId }, wordRanks(query, scope)).take(limit)
    }

    /**
     * Full-text search's notes for [query], best first, inside [scope] when given (asked wider then
     * filtered, the ranking being vault-wide); a failure leaves the meaning order alone.
     */
    suspend fun wordRanks(query: String, scope: ChatScope? = null): List<String> = try {
        val asked = if (scope == null) HybridRanking.CANDIDATES else SCOPED_WORD_CANDIDATES
        vaultDiscoveryRepository.fullTextHits(query, limit = asked, anyTerm = true)
            .map { it.path }
            .filter { scope == null || scope.contains(it) }
            .take(HybridRanking.CANDIDATES)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        /** Full-text hits read before keeping a scope's: its notes rarely lead the vault-wide ranking. */
        const val SCOPED_WORD_CANDIDATES = 200
    }
}
