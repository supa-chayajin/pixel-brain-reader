package cloud.wafflecommons.pixelbrainreader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedReviewTest {

    private val today = 20_000L

    @Test
    fun `a first Good comes back tomorrow, then the interval widens by the ease`() {
        val first = SpacedReview.next(null, SpacedReview.Answer.GOOD, today)
        assertEquals(SpacedReview.Card(interval = 1, ease = 2.5, due = today + 1), first)
        val second = SpacedReview.next(first, SpacedReview.Answer.GOOD, today + 1)
        assertEquals(3, second.interval) // max(1 + 1, round(1 × 2.5)) = 3
        val third = SpacedReview.next(second, SpacedReview.Answer.GOOD, today + 4)
        assertEquals(8, third.interval) // round(3 × 2.5)
    }

    @Test
    fun `Again brings it back tomorrow and makes it harder, never below the floor`() {
        var card = SpacedReview.Card(interval = 30, ease = 1.4, due = today)
        card = SpacedReview.next(card, SpacedReview.Answer.AGAIN, today)
        assertEquals(1, card.interval)
        assertEquals(today + 1, card.due)
        assertEquals(SpacedReview.MIN_EASE, card.ease, 1e-9)
    }

    @Test
    fun `Easy goes further and eases the note, capped at a year`() {
        val easy = SpacedReview.next(null, SpacedReview.Answer.EASY, today)
        assertEquals(4, easy.interval)
        assertEquals(2.65, easy.ease, 1e-9)
        val capped = SpacedReview.next(SpacedReview.Card(300, 2.5, today), SpacedReview.Answer.EASY, today)
        assertEquals(SpacedReview.MAX_INTERVAL_DAYS, capped.interval)
    }

    @Test
    fun `due notes first, most overdue first, then a few never seen`() {
        val cards = mapOf(
            "a.md" to SpacedReview.Card(3, 2.5, today - 5),
            "b.md" to SpacedReview.Card(3, 2.5, today - 1),
            "c.md" to SpacedReview.Card(3, 2.5, today + 2)
        )
        val scope = listOf("a.md", "b.md", "c.md") + (1..10).map { "new$it.md" }
        val due = SpacedReview.due(scope, cards, today, limit = 10)
        assertEquals(listOf("a.md", "b.md"), due.take(2))
        assertEquals(2 + SpacedReview.NEW_PER_DAY, due.size)
        assertTrue(due.drop(2).all { it.startsWith("new") })
        // Stable within a day.
        assertEquals(due, SpacedReview.due(scope, cards, today, limit = 10))
    }

    @Test
    fun `a review tag in the text or the frontmatter, but not a lookalike`() {
        assertTrue(SpacedReview.tagged("Some idea #review to keep."))
        assertTrue(SpacedReview.tagged("---\ntags: [idea, review]\n---\nText"))
        assertTrue(SpacedReview.tagged("---\ntitle: x\ntags:\n  - review\n---\nText"))
        assertFalse(SpacedReview.tagged("A #reviewer note and #review-later."))
        assertFalse(SpacedReview.tagged("---\ntags: [reviews]\n---\nText"))
        assertFalse(SpacedReview.tagged("Nothing here."))
    }

    @Test
    fun `a note is in a picked folder, not in one that only starts the same`() {
        assertTrue(SpacedReview.inFolders("30_Areas/Health/Sleep.md", setOf("30_Areas/Health")))
        assertFalse(SpacedReview.inFolders("30_Areas/HealthPlans/x.md", setOf("30_Areas/Health")))
    }
}
