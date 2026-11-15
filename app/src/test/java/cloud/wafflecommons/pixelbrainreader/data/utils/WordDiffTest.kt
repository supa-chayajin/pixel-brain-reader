package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {

    private fun marked(pieces: List<WordDiff.Piece>) = pieces.joinToString("") { if (it.changed) "[${it.text}]" else it.text }

    @Test
    fun `only the word that changed is marked, on both sides`() {
        val result = WordDiff.of("Plant tomatoes in April.", "Plant tomatoes in May.")
        assertEquals("Plant tomatoes in [April].", marked(result.old))
        assertEquals("Plant tomatoes in [May].", marked(result.new))
    }

    @Test
    fun `an added word is marked on the new side only`() {
        val result = WordDiff.of("Call the plumber", "Call the plumber today")
        assertEquals("Call the plumber", marked(result.old))
        assertEquals("Call the plumber[ today]", marked(result.new))
    }

    @Test
    fun `two neighbouring changed words read as one run`() {
        val result = WordDiff.of("rendez-vous lundi matin", "rendez-vous mardi soir")
        assertEquals("rendez-vous [mardi soir]", marked(result.new))
    }

    @Test
    fun `accented words stay whole, as on the device`() {
        assertEquals(listOf("Été", " ", "prévu", "."), WordDiff.tokens("Été prévu."))
        val result = WordDiff.of("Été prévu", "Été annulé")
        assertEquals("Été [annulé]", marked(result.new))
    }

    @Test
    fun `identical lines have nothing marked, and the pieces rebuild each line`() {
        val result = WordDiff.of("- [ ] same line", "- [ ] same line")
        assertTrue(result.new.none { it.changed })
        val other = WordDiff.of("alpha beta gamma", "alpha delta gamma epsilon")
        assertEquals("alpha beta gamma", other.old.joinToString("") { it.text })
        assertEquals("alpha delta gamma epsilon", other.new.joinToString("") { it.text })
    }

    @Test
    fun `an over-long line is marked whole`() {
        val long = (1..WordDiff.MAX_PIECES).joinToString(" ") { "w$it" }
        val result = WordDiff.of(long, "$long x")
        assertEquals(listOf(WordDiff.Piece(long, changed = true)), result.old)
    }

    @Test
    fun `removed lines pair with the added lines that follow them`() {
        val kinds = listOf(
            LineDiff.Kind.UNCHANGED, LineDiff.Kind.REMOVED, LineDiff.Kind.REMOVED, LineDiff.Kind.ADDED,
            LineDiff.Kind.UNCHANGED, LineDiff.Kind.ADDED, LineDiff.Kind.REMOVED, LineDiff.Kind.ADDED, LineDiff.Kind.ADDED
        )
        assertEquals(listOf(1 to 3, 6 to 7), WordDiff.pairs(kinds))
    }
}
