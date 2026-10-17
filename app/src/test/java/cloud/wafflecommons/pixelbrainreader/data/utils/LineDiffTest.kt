package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff.Kind.ADDED
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff.Kind.REMOVED
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff.Kind.UNCHANGED
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    private fun kinds(old: String, new: String) =
        LineDiff.compute(old, new).map { it.kind to it.text }

    @Test
    fun `identical versions produce no changed lines and fold to nothing`() {
        val text = "# Title\n\nSome body\n"
        val lines = LineDiff.compute(text, text)
        assertTrue(lines.all { it.kind == UNCHANGED })
        assertTrue(LineDiff.fold(lines).isEmpty())
    }

    @Test
    fun `a line inserted in the middle keeps its neighbours unchanged`() {
        assertEquals(
            listOf(UNCHANGED to "a", ADDED to "new", UNCHANGED to "b"),
            kinds("a\nb\n", "a\nnew\nb\n")
        )
    }

    @Test
    fun `a moved line is reported as removed and added, not unchanged`() {
        // The old set comparison called both lines "unchanged" because they exist in both versions.
        val result = kinds("first\nsecond\nthird\n", "second\nthird\nfirst\n")
        assertTrue(result.contains(REMOVED to "first"))
        assertTrue(result.contains(ADDED to "first"))
    }

    @Test
    fun `a duplicated line is reported as one addition`() {
        // A set comparison saw no change at all here (the line exists in both versions).
        val result = kinds("- [ ] task\n", "- [ ] task\n- [ ] task\n")
        assertEquals(1, result.count { it == (ADDED to "- [ ] task") })
        assertEquals(1, result.count { it == (UNCHANGED to "- [ ] task") })
        assertEquals(2, result.size)
    }

    @Test
    fun `restoring a deleted paragraph shows it as removed from the current note`() {
        // old = the historical version, new = the current note
        assertEquals(
            listOf(UNCHANGED to "keep", REMOVED to "gone"),
            kinds("keep\ngone\n", "keep\n")
        )
    }

    @Test
    fun `line endings and a missing final newline are not changes`() {
        assertTrue(LineDiff.compute("a\r\nb", "a\nb\n").all { it.kind == UNCHANGED })
    }

    @Test
    fun `an empty version diffs as all additions`() {
        assertEquals(listOf(ADDED to "x", ADDED to "y"), kinds("", "x\ny"))
    }

    @Test
    fun `fold keeps context around a change and folds the far unchanged lines`() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "line ten")

        val rows = LineDiff.fold(LineDiff.compute(old, new), context = 2)

        assertEquals(Row.Gap(7), rows.first()) // lines 1..7
        assertEquals(Row.Gap(8), rows.last()) // lines 13..20
        val shown = rows.filterIsInstance<Row.Line>()
        assertEquals(
            listOf("line 8", "line 9", "line 10", "line ten", "line 11", "line 12"),
            shown.map { it.text }
        )
    }
}
