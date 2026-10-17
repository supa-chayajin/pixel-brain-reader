package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlin.math.sqrt

/** One stored chunk as similarity scoring needs it: parsed vector, no text. */
internal class IndexedChunk(
    val id: String,
    val fileId: String,
    val isPrivate: Boolean,
    val vector: FloatArray
)

/**
 * The pure ranking step of on-device RAG: cosine similarity, the two collapse guards, and the
 * relevance floor. Settled decision #2 (CLAUDE.md): the floor is 0.35 and no score boosts — an
 * empty result means "your notes don't cover this", never off-topic notes handed to the model as
 * context. Change the floor only with a measured evaluation on a golden question set.
 */
internal object RagRanking {

    /** Calibrated on the multilingual MiniLM: same meaning EN/FR ≈ 0.97, unrelated ≈ −0.03. */
    const val MIN_SIMILARITY = 0.35f

    /**
     * A healthy query vector is ~1.0 (the graph L2-normalizes). This low only when inference
     * silently failed and left the output buffer zeroed.
     */
    const val QUERY_MAG_FLOOR = 0.5f

    /**
     * When every chunk scores nearly the same, the embedding space has collapsed (e.g. swapped
     * input tensors) and the "top-K" is ordering noise.
     */
    const val MIN_SCORE_SPREAD = 0.01f

    class Scored(val chunk: IndexedChunk, val similarity: Float)

    class Ranking(
        /** Every comparable chunk, best first (empty when the query itself was unusable). */
        val scored: List<Scored>,
        /** The chunks that clear the floor, best first — empty whenever [rejection] is set. */
        val relevant: List<Scored>,
        /** Why nothing is usable, or null. */
        val rejection: String?
    )

    fun rank(query: FloatArray, chunks: List<IndexedChunk>): Ranking {
        val queryMag = magnitude(query)
        if (queryMag < QUERY_MAG_FLOOR) {
            return Ranking(emptyList(), emptyList(), "degenerate query vector (mag=$queryMag)")
        }
        // Stale-dimension rows (an older model) are skipped instead of scoring 0 everywhere.
        val scored = chunks
            .filter { it.vector.size == query.size }
            .map { Scored(it, cosine(query, it.vector)) }
            .sortedByDescending { it.similarity }
        if (scored.isEmpty()) {
            val why = if (chunks.isEmpty()) "no embeddings yet" else "every embedding has the wrong dimension"
            return Ranking(emptyList(), emptyList(), why)
        }
        val spread = scored.first().similarity - scored.last().similarity
        if (scored.size > 1 && spread < MIN_SCORE_SPREAD) {
            return Ranking(scored, emptyList(), "collapsed embedding space (spread=$spread)")
        }
        val relevant = scored.filter { it.similarity >= MIN_SIMILARITY }
        if (relevant.isEmpty()) {
            return Ranking(scored, emptyList(), "no chunk cleared $MIN_SIMILARITY (best=${scored.first().similarity})")
        }
        return Ranking(scored, relevant, null)
    }

    fun magnitude(v: FloatArray): Float {
        var sum = 0.0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0f || normB == 0.0f) return 0.0f
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
