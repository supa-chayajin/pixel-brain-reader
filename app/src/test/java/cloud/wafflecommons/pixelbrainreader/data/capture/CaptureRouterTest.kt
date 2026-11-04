package cloud.wafflecommons.pixelbrainreader.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class CaptureRouterTest {

    private fun kind(s: String) = CaptureRouter.guess(s).kind

    @Test
    fun `explicit prefixes win and are stripped`() {
        assertEquals(CaptureGuess(CaptureKind.TASK, "pain"), CaptureRouter.guess("t: pain"))
        assertEquals(CaptureGuess(CaptureKind.GRATITUDE, "le soleil"), CaptureRouter.guess("G: le soleil"))
        assertEquals(CaptureGuess(CaptureKind.IDEA, "app vélo"), CaptureRouter.guess("i:app vélo"))
        assertEquals(CaptureGuess(CaptureKind.SCRAP, "merci"), CaptureRouter.guess("s: merci"))
        assertEquals(CaptureGuess(CaptureKind.TASK, "rendre le livre"), CaptureRouter.guess("- [ ] rendre le livre"))
    }

    @Test
    fun `moment prefix reads a leading time`() {
        assertEquals(
            CaptureGuess(CaptureKind.MOMENT, "café avec Léa", LocalTime.of(9, 15)),
            CaptureRouter.guess("m: 9h15 café avec Léa")
        )
        assertEquals(CaptureGuess(CaptureKind.MOMENT, "sieste"), CaptureRouter.guess("m: sieste"))
    }

    @Test
    fun `gratitude words`() {
        assertEquals(CaptureKind.GRATITUDE, kind("Merci à maman pour le repas"))
        assertEquals(CaptureKind.GRATITUDE, kind("reconnaissante pour la balade"))
        assertEquals(CaptureKind.GRATITUDE, kind("Thanks to the team"))
        // "mercredi" starts with "merci"? No — "merc" + "r": not a word match.
        assertEquals(CaptureKind.SCRAP, kind("mercredi pluvieux"))
    }

    @Test
    fun `idea openers keep the idea and drop the label`() {
        assertEquals(CaptureGuess(CaptureKind.IDEA, "un widget météo"), CaptureRouter.guess("Idée : un widget météo"))
        assertEquals(CaptureGuess(CaptureKind.IDEA, "et si on partait"), CaptureRouter.guess("et si on partait"))
        assertEquals(CaptureKind.IDEA, kind("What if notes had colours"))
    }

    @Test
    fun `to-do verbs make tasks, with a time when written`() {
        assertEquals(CaptureGuess(CaptureKind.TASK, "Acheter du pain"), CaptureRouter.guess("Acheter du pain"))
        assertEquals(
            CaptureGuess(CaptureKind.TASK, "appeler la banque à 14h", LocalTime.of(14, 0)),
            CaptureRouter.guess("appeler la banque à 14h")
        )
        assertEquals(LocalTime.of(16, 30), CaptureRouter.guess("buy milk 16:30").time)
        assertEquals(CaptureKind.TASK, kind("Penser à arroser les plantes"))
        // "fixer" is not "fix ".
        assertEquals(CaptureKind.SCRAP, kind("fixer le cadre plus tard"))
    }

    @Test
    fun `a leading time is a moment`() {
        assertEquals(
            CaptureGuess(CaptureKind.MOMENT, "appel avec la banque", LocalTime.of(14, 0)),
            CaptureRouter.guess("14h appel avec la banque")
        )
        assertEquals(LocalTime.of(8, 5), CaptureRouter.guess("08:05 - train").time)
        // A bare time with nothing after it is not a moment.
        assertEquals(CaptureKind.SCRAP, kind("14h"))
    }

    @Test
    fun `times that are not times are ignored`() {
        assertNull(CaptureRouter.findTime("3hrs of work"))
        assertNull(CaptureRouter.findTime("25h00"))
        assertNull(CaptureRouter.findTime("a12h"))
        assertNull(CaptureRouter.findTime("9:5"))
        assertEquals(LocalTime.of(7, 0), CaptureRouter.findTime("lever 7h demain"))
    }

    @Test
    fun `everything else lands in the scratchpad`() {
        assertEquals(CaptureGuess(CaptureKind.SCRAP, "Belle lumière ce soir"), CaptureRouter.guess("  Belle lumière ce soir \n"))
        assertEquals(CaptureKind.SCRAP, kind(""))
    }

    @Test
    fun `next cycles through every destination`() {
        var k = CaptureKind.TASK
        val seen = mutableSetOf<CaptureKind>()
        repeat(CaptureKind.entries.size) { seen += k; k = k.next() }
        assertEquals(CaptureKind.entries.toSet(), seen)
        assertEquals(CaptureKind.TASK, k)
    }
}
