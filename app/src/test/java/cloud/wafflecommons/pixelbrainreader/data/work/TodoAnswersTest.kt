package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Typed hand-offs and answers to the live TODO's questions (C13 upgrades). */
class TodoAnswersTest {

    private val today = LocalDate.of(2026, 11, 6)

    private val todo = """
        ---
        title: "TODO"
        date_updated: '2026-11-01'
        ---
        # TODO

        ## ❓ Open questions

        - [ ] The RetryPolicy ceiling.
          - 💬 2026-11-05 — First thought: 3.
        - [ ] The database-path gap
        - [ ] Should the skeleton expose port 80?

        ## 🧇 Next (Claude, on your go)

        - [ ] Cut the branches
        - [ ] question: keep Predis 2?
    """.trimIndent()

    @Test
    fun `typed hand-offs start with the French prefix the desktop reads`() {
        assertEquals("question: Faut-il garder Predis ?", DeskHandoffFormat.typed("question", "  Faut-il garder Predis ? "))
        assertEquals("tâche: Relire [[Plan]]", DeskHandoffFormat.typed("tâche", "Relire [[Plan]]"))
        assertEquals("Relire", DeskHandoffFormat.typed(null, "Relire"))
        assertEquals("Relire", DeskHandoffFormat.typed("anything", "Relire"))
        assertEquals(listOf("tâche", "question", "décision"), DeskHandoffFormat.KINDS)
    }

    @Test
    fun `questions are the lines under a question heading, typed, or asking`() {
        val queue = ProjectFormat.todoActions("20_Projects/21_Demo/TODO.md", todo)
        assertEquals(
            listOf("The RetryPolicy ceiling.", "The database-path gap", "Should the skeleton expose port 80?", "question: keep Predis 2?"),
            queue.filter(TodoAnswers::isQuestion).map { it.text }
        )
        assertFalse(TodoAnswers.isQuestion(queue.single { it.text == "Cut the branches" }))
        // Each line keeps its source, which the answer is attached under.
        assertEquals("- [ ] The database-path gap", queue[1].sourceLine)
    }

    @Test
    fun `an answer goes under its question, after what is already nested there`() {
        val answered = TodoAnswers.answer(todo, "- [ ] The RetryPolicy ceiling.", "5 retries,\nthen the dead-letter queue.", today)!!
        val lines = answered.lines()
        val q = lines.indexOf("- [ ] The RetryPolicy ceiling.")
        assertEquals("  - 💬 2026-11-05 — First thought: 3.", lines[q + 1])
        assertEquals("  - 💬 2026-11-06 — 5 retries, then the dead-letter queue.", lines[q + 2])
        assertEquals("- [ ] The database-path gap", lines[q + 3])
        assertTrue(answered.contains("date_updated: '2026-11-06'"))
        // The answer is not a checkbox: it never becomes an action of the queue.
        assertEquals(5, ProjectFormat.todoActions("20_Projects/21_Demo/TODO.md", answered).size)

        assertNull(TodoAnswers.answer(todo, "- [ ] A line that is gone", "x", today))
    }
}
