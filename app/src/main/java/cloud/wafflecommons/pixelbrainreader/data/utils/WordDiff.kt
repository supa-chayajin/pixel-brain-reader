package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * The words that changed inside a changed line (K3, Time Machine v2, 12.20): the old and new lines
 * cut into words, spaces and signs, matched by their longest common run, and each piece marked
 * changed or not. Words are letters and digits by an explicit class, so the JVM tests and Android's
 * ICU engine cut the same way. Pure.
 */
object WordDiff {

    /** A piece of a line, and whether it is part of what changed. */
    data class Piece(val text: String, val changed: Boolean)

    data class Result(val old: List<Piece>, val new: List<Piece>)

    /** Beyond this many pieces a side, the whole line is shown as changed (the match is quadratic). */
    const val MAX_PIECES = 400

    private val PIECE = Regex("[\\p{L}\\p{N}_]+|\\s+|.")

    fun tokens(line: String): List<String> = PIECE.findAll(line).map { it.value }.toList()

    fun of(old: String, new: String): Result {
        val a = tokens(old)
        val b = tokens(new)
        if (a.size > MAX_PIECES || b.size > MAX_PIECES) {
            return Result(listOf(Piece(old, changed = true)), listOf(Piece(new, changed = true)))
        }
        // Longest common subsequence over the pieces, filled from the end.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val oldPieces = mutableListOf<Piece>()
        val newPieces = mutableListOf<Piece>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    oldPieces += Piece(a[i++], changed = false)
                    newPieces += Piece(b[j++], changed = false)
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> oldPieces += Piece(a[i++], changed = true)
                else -> newPieces += Piece(b[j++], changed = true)
            }
        }
        while (i < a.size) oldPieces += Piece(a[i++], changed = true)
        while (j < b.size) newPieces += Piece(b[j++], changed = true)
        return Result(merge(oldPieces), merge(newPieces))
    }

    /**
     * Neighbouring pieces with the same mark joined, and a lone unchanged space between two changed
     * pieces counted as changed: "a b" replaced by "x y" highlights as one run, not two words.
     */
    private fun merge(pieces: List<Piece>): List<Piece> {
        val bridged = pieces.mapIndexed { index, piece ->
            val betweenChanges = !piece.changed && piece.text.isBlank() &&
                pieces.getOrNull(index - 1)?.changed == true && pieces.getOrNull(index + 1)?.changed == true
            if (betweenChanges) piece.copy(changed = true) else piece
        }
        val out = mutableListOf<Piece>()
        for (piece in bridged) {
            val last = out.lastOrNull()
            if (last != null && last.changed == piece.changed) {
                out[out.lastIndex] = Piece(last.text + piece.text, piece.changed)
            } else {
                out += piece
            }
        }
        return out
    }

    /**
     * The changed lines of a line diff paired for word highlights: in each run of removed lines
     * followed by added lines, the i-th removed with the i-th added. Indexes into [kinds].
     */
    fun pairs(kinds: List<LineDiff.Kind>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < kinds.size) {
            if (kinds[index] != LineDiff.Kind.REMOVED) {
                index++
                continue
            }
            val removedStart = index
            while (index < kinds.size && kinds[index] == LineDiff.Kind.REMOVED) index++
            val addedStart = index
            while (index < kinds.size && kinds[index] == LineDiff.Kind.ADDED) index++
            val count = minOf(addedStart - removedStart, index - addedStart)
            for (k in 0 until count) out += (removedStart + k) to (addedStart + k)
        }
        return out
    }
}
