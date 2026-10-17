package cloud.wafflecommons.pixelbrainreader.data.utils

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

/**
 * Line-level diff of two versions of a note, computed with JGit's histogram algorithm — the
 * same one `git diff` uses — so moved, duplicated and reordered lines are reported the way git
 * reports them. (A set comparison, which is what this replaced, marks a line "unchanged" as
 * soon as an identical line exists anywhere in the other version.)
 */
object LineDiff {

    enum class Kind { UNCHANGED, REMOVED, ADDED }

    sealed interface Row {
        data class Line(val kind: Kind, val text: String) : Row

        /** A run of [count] unchanged lines folded away because it is far from any change. */
        data class Gap(val count: Int) : Row
    }

    /** Every line of [new] and every line removed from [old], in reading order. */
    fun compute(old: String, new: String): List<Row.Line> {
        val a = RawText(normalize(old).toByteArray(Charsets.UTF_8))
        val b = RawText(normalize(new).toByteArray(Charsets.UTF_8))
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b)

        val rows = mutableListOf<Row.Line>()
        var nextA = 0
        for (edit in edits) {
            // Lines before the edit are common to both versions (same count on each side).
            for (i in nextA until edit.beginA) rows += Row.Line(Kind.UNCHANGED, a.getString(i))
            for (i in edit.beginA until edit.endA) rows += Row.Line(Kind.REMOVED, a.getString(i))
            for (i in edit.beginB until edit.endB) rows += Row.Line(Kind.ADDED, b.getString(i))
            nextA = edit.endA
        }
        for (i in nextA until a.size()) rows += Row.Line(Kind.UNCHANGED, a.getString(i))
        return rows
    }

    /**
     * Keeps [context] unchanged lines around each change and folds every other run of unchanged
     * lines into a [Row.Gap]. Returns an empty list when the versions are identical.
     */
    fun fold(lines: List<Row.Line>, context: Int = 3): List<Row> {
        val changed = lines.indices.filter { lines[it].kind != Kind.UNCHANGED }
        if (changed.isEmpty()) return emptyList()

        val keep = BooleanArray(lines.size)
        for (i in changed) {
            for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.lastIndex)) {
                keep[j] = true
            }
        }
        val rows = mutableListOf<Row>()
        var folded = 0
        lines.forEachIndexed { i, line ->
            if (keep[i]) {
                if (folded > 0) rows += Row.Gap(folded)
                folded = 0
                rows += line
            } else {
                folded++
            }
        }
        if (folded > 0) rows += Row.Gap(folded)
        return rows
    }

    /** CRLF and a missing final newline are not content changes worth showing in a note. */
    private fun normalize(text: String): String {
        val unix = text.replace("\r\n", "\n")
        return if (unix.isEmpty() || unix.endsWith("\n")) unix else "$unix\n"
    }
}
