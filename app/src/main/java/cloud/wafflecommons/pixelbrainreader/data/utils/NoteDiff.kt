package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Two versions of a note as the history views show them (K3, K4, 12.20): the folded line diff and,
 * inside each pair of changed lines, the words that changed. Pure.
 */
data class NoteDiff(val rows: List<LineDiff.Row>, val words: Map<Int, List<WordDiff.Piece>>) {
    companion object {
        fun of(old: String, new: String): NoteDiff {
            val rows = LineDiff.fold(LineDiff.compute(old = old, new = new))
            val kinds = rows.map { (it as? LineDiff.Row.Line)?.kind ?: LineDiff.Kind.UNCHANGED }
            val words = HashMap<Int, List<WordDiff.Piece>>()
            for ((removed, added) in WordDiff.pairs(kinds)) {
                val result = WordDiff.of((rows[removed] as LineDiff.Row.Line).text, (rows[added] as LineDiff.Row.Line).text)
                words[removed] = result.old
                words[added] = result.new
            }
            return NoteDiff(rows, words)
        }
    }
}
