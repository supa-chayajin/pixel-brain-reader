package cloud.wafflecommons.pixelbrainreader.data.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** Turns what the user typed into a safe FTS4 MATCH expression. */
object FtsQuery {

    private val TERM = Regex("""[\p{L}\p{N}]+""")

    /**
     * Every word becomes a prefix term (`jgit* reba*`), implicitly AND-ed, so results narrow while
     * typing. Only letters and digits survive, so no user input can reach FTS syntax (quotes, `-`,
     * `NEAR`, column filters). Null when nothing searchable is left.
     */
    fun build(raw: String, maxTerms: Int = 8): String? {
        val terms = TERM.findAll(raw.lowercase()).map { it.value }.distinct().take(maxTerms).toList()
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }
}

/**
 * Okapi BM25 over SQLite's `matchinfo(…, 'pcnalx')` — FTS4 counts the terms, this turns the counts
 * into a relevance score. Column weights let a hit in the path (≈ the title) outrank one in the body.
 */
object Bm25 {

    private const val K1 = 1.2
    private const val B = 0.75

    /** SQLite writes matchinfo as unsigned 32-bit integers in the device's byte order. */
    fun decode(blob: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buffer.getInt(it * 4) }
    }

    /** [weights] per column, in table order; missing weights count 1. */
    fun score(matchInfo: IntArray, weights: DoubleArray = doubleArrayOf()): Double {
        if (matchInfo.size < 3) return 0.0
        val phrases = matchInfo[0]
        val columns = matchInfo[1]
        val rows = matchInfo[2].toDouble()
        val averageOffset = 3
        val lengthOffset = averageOffset + columns
        val hitsOffset = lengthOffset + columns
        if (matchInfo.size < hitsOffset + 3 * phrases * columns) return 0.0

        var score = 0.0
        for (phrase in 0 until phrases) {
            for (column in 0 until columns) {
                val base = hitsOffset + 3 * (phrase * columns + column)
                val hitsHere = matchInfo[base].toDouble()
                if (hitsHere == 0.0) continue
                val docsWithHit = matchInfo[base + 2].toDouble()
                val idf = ln(1.0 + (rows - docsWithHit + 0.5) / (docsWithHit + 0.5))
                val average = matchInfo[averageOffset + column].toDouble().coerceAtLeast(1.0)
                val length = matchInfo[lengthOffset + column].toDouble()
                val tf = hitsHere * (K1 + 1) / (hitsHere + K1 * (1 - B + B * length / average))
                score += weights.getOrElse(column) { 1.0 } * idf * tf
            }
        }
        return score
    }
}

/** A ranked full-text hit: the note, its BM25 score and an excerpt with `«matched»` words. */
data class FullTextHit(val path: String, val score: Double, val excerpt: String)
