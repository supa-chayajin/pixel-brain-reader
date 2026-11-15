package cloud.wafflecommons.pixelbrainreader.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLayoutTest {

    @Test
    fun `alternating questions and answers are each alone`() {
        assertEquals(
            listOf(BubbleRun.ALONE, BubbleRun.ALONE, BubbleRun.ALONE),
            BubbleRun.of(listOf(true, false, true))
        )
    }

    @Test
    fun `a run of one side has a first, middles and a last`() {
        assertEquals(
            listOf(BubbleRun.ALONE, BubbleRun.FIRST, BubbleRun.MIDDLE, BubbleRun.LAST, BubbleRun.ALONE),
            BubbleRun.of(listOf(false, true, true, true, false))
        )
        assertEquals(listOf(BubbleRun.FIRST, BubbleRun.LAST), BubbleRun.of(listOf(true, true)))
        assertEquals(emptyList<BubbleRun>(), BubbleRun.of(emptyList()))
    }

    @Test
    fun `a run's bubbles know their neighbours`() {
        assertTrue(BubbleRun.MIDDLE.continuesAbove && BubbleRun.MIDDLE.continuesBelow)
        assertTrue(BubbleRun.LAST.continuesAbove && !BubbleRun.LAST.continuesBelow)
        assertTrue(!BubbleRun.FIRST.continuesAbove && BubbleRun.FIRST.continuesBelow)
        assertFalse(BubbleRun.ALONE.continuesAbove || BubbleRun.ALONE.continuesBelow)
    }

    @Test
    fun `a source reads as its title and its folder as people say it`() {
        assertEquals("README · Garden Planner", SourceLabel.of("20_Projects/21_Garden_Planner/README.md"))
        assertEquals("2026-04-02 · Journal", SourceLabel.of("10_Journal/2026-04-02.md"))
        assertEquals("Idea", SourceLabel.of("Idea.md"))
        assertEquals("Secret · Private", SourceLabel.of("99_Private/Secret.md.enc"))
    }
}
