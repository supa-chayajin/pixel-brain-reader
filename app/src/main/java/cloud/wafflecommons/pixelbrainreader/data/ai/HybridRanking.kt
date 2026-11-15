package cloud.wafflecommons.pixelbrainreader.data.ai

/**
 * Hybrid retrieval (R2, 12.20): the passages found by meaning, re-ranked by reciprocal-rank fusion
 * with where full-text search ranks their note.
 *
 * Words only re-rank (the owner's D2, 2026-11-15): every result is one of [fuse]'s `byMeaning`,
 * which all passed the 0.35 floor (settled decision #2). A question the vault doesn't answer stays
 * as empty as before, and no note reaches an answer on shared words alone. The golden set showed
 * the gain this can reach: recall@5 by meaning 0.77, by words 0.82, either 0.93 (the Fold,
 * 2026-11-15). Pure.
 */
object HybridRanking {

    /** Candidates asked of each search before fusing (the plan's top 20). */
    const val CANDIDATES = 20

    /** The usual reciprocal-rank constant: rank r (from 0) weighs 1 / (RRF_K + r + 1). */
    const val RRF_K = 60

    /**
     * [byMeaning] (best first) re-ordered by its meaning rank plus its note's rank in [byWords]
     * (note paths, best first). A passage whose note shares no word keeps only its meaning weight;
     * equal scores keep the meaning order. Every passage of a note gets that note's word rank.
     */
    fun <T> fuse(byMeaning: List<T>, noteOf: (T) -> String, byWords: List<String>): List<T> {
        if (byMeaning.isEmpty()) return emptyList()
        val wordRank = HashMap<String, Int>()
        byWords.forEachIndexed { rank, path -> wordRank.putIfAbsent(path, rank) }
        if (wordRank.isEmpty()) return byMeaning
        // sortedByDescending is stable: ties keep the meaning order.
        return byMeaning.withIndex()
            .sortedByDescending { (rank, hit) -> weight(rank) + (wordRank[noteOf(hit)]?.let(::weight) ?: 0.0) }
            .map { it.value }
    }

    private fun weight(rank: Int): Double = 1.0 / (RRF_K + rank + 1)
}
