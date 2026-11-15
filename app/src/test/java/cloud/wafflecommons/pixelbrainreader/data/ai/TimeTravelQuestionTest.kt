package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimeTravelQuestionTest {

    private val today = LocalDate.of(2026, 11, 15)
    private fun then(question: String) = TimeTravelQuestion.parse(question, today)?.then

    @Test
    fun `il y a un an, and the question without it`() {
        val parsed = TimeTravelQuestion.parse("Qu'est-ce que je pensais de Dune il y a un an ?", today)!!
        assertEquals(LocalDate.of(2025, 11, 15), parsed.then)
        assertTrue(parsed.topic.contains("Dune"))
        assertFalse(parsed.topic.contains("il y a"))
    }

    @Test
    fun `months, weeks and days ago, in words or digits`() {
        assertEquals(LocalDate.of(2026, 5, 15), then("Mon avis sur le jardin il y a 6 mois ?"))
        assertEquals(LocalDate.of(2026, 11, 1), then("Où en était le projet il y a deux semaines ?"))
        assertEquals(LocalDate.of(2024, 11, 15), then("What did I think about the garden 2 years ago?"))
        assertEquals(LocalDate.of(2025, 11, 15), then("What did I write about it a year ago?"))
    }

    @Test
    fun `last year, a month or a year by name`() {
        assertEquals(LocalDate.of(2025, 11, 15), then("Mon plan l'année dernière ?"))
        assertEquals(LocalDate.of(2025, 11, 15), then("My plan last year?"))
        assertEquals(LocalDate.of(2025, 3, 31), then("Que disais-je du budget en mars 2025 ?"))
        assertEquals(LocalDate.of(2025, 2, 28), then("Le budget en février 2025 ?"))
        assertEquals(LocalDate.of(2025, 3, 31), then("The budget in March 2025?"))
        assertEquals(LocalDate.of(2024, 12, 31), then("Mes objectifs en 2024 ?"))
    }

    @Test
    fun `no time, or one not in the past, is no time travel`() {
        assertNull(TimeTravelQuestion.parse("Que penser de Dune ?", today))
        assertNull(then("Mes objectifs en 2026 ?"))
        assertNull(then("Mes objectifs en 2030 ?"))
        assertNull(then("Il y a des tomates ?"))
    }
}
