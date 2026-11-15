package cloud.wafflecommons.pixelbrainreader.data.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The People note format (12.19, P1; the owner's D8): what the app writes, it reads back whole. */
class PersonNoteTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun full(): PersonNote = PersonNoteFormat.create("Alex Martin", PersonKind.WORK, "Tech lead", today.minusDays(30)).copy(
        updated = today,
        context = "Joined in March.\n\nLikes written agendas.",
        openPoints = listOf(CheckItem("Budget for Q1", today), CheckItem("Training plan", today.minusDays(3), done = true, doneOn = today)),
        myActions = listOf(CheckItem("Send the roadmap (draft)", today)),
        theirActions = listOf(CheckItem("Review the PR", today.minusDays(1))),
        oneOnOnes = listOf(OneOnOne(today, "Budget.\n- point A\n- point B"), OneOnOne(today.minusDays(7), "Talked about growth.")),
        feedback = listOf(Feedback(today, given = true, text = "Great demo"), Feedback(today.minusDays(2), given = false, text = "More context in tickets")),
        personal = "Two kids.\nBirthday 12 May."
    )

    @Test
    fun `a full note renders and reads back the same`() {
        val note = full()

        val back = PersonNoteFormat.parse(PersonNoteFormat.render(note), today)

        assertEquals(note, back)
    }

    @Test
    fun `the frontmatter is the vault's, plus the kind and the role`() {
        val text = PersonNoteFormat.render(full())

        assertTrue(text.startsWith("---\ntitle: \"Alex Martin\"\ndate_created: '2026-10-16'\ndate_updated: '2026-11-15'\ntype: private\n"))
        assertTrue(text.contains("\nkind: pro\nrole: \"Tech lead\"\n---\n# Alex Martin\n"))
        assertTrue(text.contains("\n## Points ouverts\n- [ ] Budget for Q1 (2026-11-15)\n- [x] Training plan (2026-11-12) ✅ 2026-11-15\n"))
        assertTrue(text.contains("\n## 1:1\n### 2026-11-15\nBudget."))
        assertTrue(text.contains("\n## Feedback\n- 2026-11-15 — donné : Great demo\n- 2026-11-13 — reçu : More context in tickets\n"))
    }

    @Test
    fun `what the format doesn't know survives a round trip`() {
        val text = PersonNoteFormat.render(full()).replace(
            "role: \"Tech lead\"\n",
            "role: \"Tech lead\"\nemail: a@example.org\nlinks:\n  - one\n"
        ).replace(
            "## Mes actions\n",
            "## Mes actions\nA line of my own\n"
        ) + "\n## Objectifs\nGrow into a staff role.\n"

        val note = PersonNoteFormat.parse(text, today)
        val again = PersonNoteFormat.render(note)

        assertEquals(listOf("email: a@example.org", "links:", "  - one"), note.kept.frontmatter)
        assertEquals(listOf("A line of my own"), note.kept.lines[PersonNoteFormat.MY_ACTIONS])
        assertEquals(listOf("Objectifs" to "Grow into a staff role."), note.kept.sections)
        assertTrue(again.contains("email: a@example.org\nlinks:\n  - one\n---"))
        assertTrue(again.contains("A line of my own"))
        assertTrue(again.trimEnd().endsWith("## Objectifs\nGrow into a staff role."))
        assertEquals(note, PersonNoteFormat.parse(again, today))
    }

    @Test
    fun `a heading typed in free text can't open a section`() {
        val note = full().copy(context = "## Secret plans\n### More\nplain")

        val back = PersonNoteFormat.parse(PersonNoteFormat.render(note), today)

        assertEquals("#### Secret plans\n#### More\nplain", back.context)
        assertEquals(full().openPoints, back.openPoints)
    }

    @Test
    fun `an unknown or missing kind counts as work`() {
        assertEquals(PersonKind.WORK, PersonKind.of("collegue"))
        assertEquals(PersonKind.WORK, PersonKind.of(null))
        assertEquals(PersonKind.PERSONAL, PersonKind.of(" Perso "))
    }

    @Test
    fun `the counts the list shows`() {
        val note = full()

        assertEquals(1, note.openPointsLeft)
        assertEquals(2, note.actionsLeft)
        assertEquals(today, note.lastOneOnOne)
    }
}
