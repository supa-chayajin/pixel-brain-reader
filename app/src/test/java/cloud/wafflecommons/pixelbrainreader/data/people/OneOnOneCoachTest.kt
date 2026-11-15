package cloud.wafflecommons.pixelbrainreader.data.people

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Ask Coach to prepare a 1:1 (12.19, the owner's D9), and which people a mode shows (D1). */
class OneOnOneCoachTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun note(kind: PersonKind = PersonKind.WORK) = PersonNoteFormat.create("Élodie Martin", kind, "Tech lead", today).copy(
        context = "A secret context",
        personal = "Her birthday is in May",
        feedback = listOf(Feedback(today, given = true, text = "A private compliment")),
        openPoints = listOf(CheckItem("Ask élodie about the budget", today), CheckItem("An old point", today, done = true, doneOn = today)),
        myActions = listOf(CheckItem("Send MARTIN the roadmap", today)),
        theirActions = listOf(CheckItem("Review the PR", today)),
        oneOnOnes = listOf(OneOnOne(today.minusDays(7), "Notes"))
    )

    @Test
    fun `the brief never carries the name, even typed in a point`() {
        val prompt = OneOnOneCoach.prompt(note(), today)

        assertFalse(prompt.contains("lodie", ignoreCase = true))
        assertFalse(prompt.contains("martin", ignoreCase = true))
        assertTrue(prompt.contains("Point ouvert : Ask X about the budget"))
        assertTrue(prompt.contains("Mon action en cours : Send X the roadmap"))
        assertTrue(prompt.contains("Son action en cours : Review the PR"))
        assertTrue(prompt.contains("rôle : Tech lead"))
        assertTrue(prompt.contains("il y a") || prompt.contains("7 jours"))
    }

    @Test
    fun `context, personal details, feedback and done points stay out`() {
        val prompt = OneOnOneCoach.prompt(note(), today)

        assertFalse(prompt.contains("secret context"))
        assertFalse(prompt.contains("birthday"))
        assertFalse(prompt.contains("compliment"))
        assertFalse(prompt.contains("An old point"))
    }

    @Test
    fun `it stays within Coach's budget and ends with the French directive`() {
        val many = note().copy(openPoints = List(60) { CheckItem("Point number $it with a long explanation to fill the brief", today) })

        val prompt = OneOnOneCoach.prompt(many, today)

        assertTrue(prompt.length <= 1_200)
        assertTrue(prompt.trimEnd().endsWith(AiLanguage.DIRECTIVE))
    }

    @Test
    fun `a personal person is a close one, and a first one-on-one is said so`() {
        val prompt = OneOnOneCoach.prompt(note(PersonKind.PERSONAL).copy(oneOnOnes = emptyList()), today)

        assertTrue(prompt.contains("un proche"))
        assertTrue(prompt.contains("premier tête-à-tête"))
    }

    @Test
    fun `outside Work mode only personal people and unreadable notes show`() {
        val work = Person("00000001", note(PersonKind.WORK))
        val personal = Person("00000002", note(PersonKind.PERSONAL))
        val unreadable = Person("00000003", null)

        assertEquals(listOf(personal, unreadable), People.visible(listOf(work, personal, unreadable), workHidden = true))
        assertEquals(3, People.visible(listOf(work, personal, unreadable), workHidden = false).size)
    }
}
