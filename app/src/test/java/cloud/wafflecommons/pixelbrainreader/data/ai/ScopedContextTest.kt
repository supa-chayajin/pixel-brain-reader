package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedContextTest {

    private val notes: NoteRepository = mockk()
    private val ai: LocalAiManager = mockk()
    private val context = ScopedContext(notes, ai)
    private val scope = ChatScope.note("20_Projects/21_Garden/Plan.md")!!
    private val progress = mutableListOf<String>()

    @Test
    fun `a note that fits is read whole, frontmatter aside, without a model call`() = runTest {
        coEvery { notes.getNoteContent(scope.path) } returns "---\ntitle: Plan\n---\nTomatoes in April."

        val reading = context.readNote(scope, "When?") { progress += it }!!

        assertEquals("[Note : Plan]\nTomatoes in April.", reading.context)
        assertEquals(listOf(scope.path), reading.sources)
        coVerify(exactly = 0) { ai.generateResponse(any()) }
    }

    @Test
    fun `a longer note is summarized part by part, then the answer reads the useful summaries`() = runTest {
        val paragraph = "x".repeat(3_000)
        coEvery { notes.getNoteContent(scope.path) } returns listOf(paragraph, paragraph, paragraph).joinToString("\n\n")
        coEvery { ai.generateResponse(any()) } returnsMany listOf(Result.success("RIEN"), Result.success("Tomates en avril."), Result.success("Rien."))

        val reading = context.readNote(scope, "Quand ?") { progress += it }!!

        assertEquals("[Note : Plan — résumé de la partie 2 sur 3]\nTomates en avril.", reading.context)
        assertEquals(listOf("Reading Plan: part 1 of 3…", "Reading Plan: part 2 of 3…", "Reading Plan: part 3 of 3…"), progress)
        coVerify(exactly = 3) { ai.generateResponse(match { it.contains("« Quand ? »") }) }
    }

    @Test
    fun `a failing summary stops the reading`() = runTest {
        val paragraph = "x".repeat(3_000)
        coEvery { notes.getNoteContent(scope.path) } returns "$paragraph\n\n$paragraph"
        coEvery { ai.generateResponse(any()) } returns Result.failure(NanoException.Unavailable("no model"))

        val reading = context.readNote(scope, "Quand ?") { progress += it }!!

        assertNull(reading.context)
        coVerify(exactly = 1) { ai.generateResponse(any()) }
    }

    @Test
    fun `a note too long for chained summaries is left to the closest passages`() = runTest {
        val paragraph = "x".repeat(3_900)
        coEvery { notes.getNoteContent(scope.path) } returns (1..ScopedReading.MAX_PARTS + 1).joinToString("\n\n") { paragraph }

        assertNull(context.readNote(scope, "Quand ?") { progress += it })
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `a note that can't be read gives no context and no source`() = runTest {
        coEvery { notes.getNoteContent(scope.path) } returns null

        assertEquals(ScopedContext.Reading(null, emptyList()), context.readNote(scope, "Quand ?") { progress += it })
    }
}
