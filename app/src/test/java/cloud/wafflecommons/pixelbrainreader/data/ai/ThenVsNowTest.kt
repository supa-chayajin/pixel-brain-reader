package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ThenVsNowTest {

    private val day = LocalDate.of(2025, 11, 15)

    @Test
    fun `each note then and now, frontmatter aside, with what was missing said plainly`() {
        val context = ThenVsNow.context(
            listOf(
                ThenVsNow.Note("Garden/Plan.md", then = "---\ntags: []\n---\nTomates en avril.", now = "Tomates en mai."),
                ThenVsNow.Note("Garden/New.md", then = null, now = "Une serre.")
            ),
            day
        )!!
        assertTrue(context.contains("[Note : Plan — telle qu'elle était le 15 novembre 2025]\nTomates en avril."))
        assertTrue(context.contains("[Note : Plan — aujourd'hui]\nTomates en mai."))
        assertTrue(context.contains("[Note : New — telle qu'elle était le 15 novembre 2025]\n(pas encore écrite à cette date)"))
    }

    @Test
    fun `no note at all, then or now, is no context`() {
        assertNull(ThenVsNow.context(listOf(ThenVsNow.Note("x.md", null, null)), day))
        assertNull(ThenVsNow.context(emptyList(), day))
    }

    @Test
    fun `the question asks the comparison in French and ends with the directive`() {
        val asked = ThenVsNow.ask("Qu'est-ce que je pensais du jardin ?", day)
        assertTrue(asked.startsWith("Qu'est-ce que je pensais du jardin ?"))
        assertTrue(asked.contains("le 15 novembre 2025"))
        assertTrue(asked.endsWith(AiLanguage.DIRECTIVE))
    }

    @Test
    fun `the answer keeps the day in its sources, and the notes come back without it`() {
        val sources = listOf(ThenVsNow.mark(day), "Garden/Plan.md")
        assertEquals(day, ThenVsNow.dayOf(sources))
        assertEquals(listOf("Garden/Plan.md"), ThenVsNow.notesOf(sources))
        assertNull(ThenVsNow.dayOf(listOf("Garden/Plan.md")))
    }

    @Test
    fun `a note as of a day is its newest version by then, under the name it had`() = runTest {
        val git: JGitProvider = mockk()
        val notes: NoteRepository = mockk()
        val travel = NoteTimeTravel(git, notes)
        fun millis(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli()
        coEvery { git.getFileCommitHistory("Garden/Plan.md", any()) } returns listOf(
            GitCommitDto("c3", "c3", "me", "later", millis(day.plusDays(10))),
            GitCommitDto("c2", "c2", "me", "that day", millis(day), path = "Old/Plan.md"),
            GitCommitDto("c1", "c1", "me", "before", millis(day.minusDays(30)), path = "Old/Plan.md")
        )
        coEvery { git.getFileContentAtCommit("Old/Plan.md", "c2") } returns "Tomates en avril."

        assertEquals("Tomates en avril.", travel.asOf("Garden/Plan.md", day))
        assertNull(travel.asOf("Garden/Plan.md", day.minusDays(60)))
    }
}
