package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScopeTest {

    @Test
    fun `a note and a folder read as people say them`() {
        assertEquals("README", ChatScope.note("20_Projects/21_Garden_Planner/README.md")!!.label)
        assertEquals("Garden Planner", ChatScope.folder("20_Projects/21_Garden_Planner/")!!.label)
    }

    @Test
    fun `a folder holds what is under it, a note only itself`() {
        val folder = ChatScope.folder("20_Projects/21_Garden")!!
        assertTrue(folder.contains("20_Projects/21_Garden/README.md"))
        assertTrue(folder.contains("20_Projects/21_Garden/Notes/Seeds.md"))
        assertFalse(folder.contains("20_Projects/21_Garden_Planner/README.md"))
        val note = ChatScope.note("10_Journal/2026-04-02.md")!!
        assertTrue(note.contains("10_Journal/2026-04-02.md"))
        assertFalse(note.contains("10_Journal/2026-04-03.md"))
    }

    @Test
    fun `private notes and folders can't be asked, nor what isn't a note`() {
        assertNull(ChatScope.note("99_Private/Journal.md"))
        assertNull(ChatScope.folder("99_Private"))
        assertNull(ChatScope.folder("99_Private/People"))
        assertNull(ChatScope.note("10_Journal/data/health/metrics/2026-11-15.json"))
        assertNull(ChatScope.folder(""))
        assertTrue(ChatScope.allows("99_Privateer/Note.md", isFolder = false))
    }

    @Test
    fun `a question keeps its scope in its sources and gets it back`() {
        val folder = ChatScope.folder("20_Projects/21_Garden")!!
        assertEquals(folder, ChatScope.decode(listOf(folder.encode())))
        val note = ChatScope.note("Ideas.md")!!
        assertEquals(note, ChatScope.decode(listOf("other", note.encode())))
        assertNull(ChatScope.decode(emptyList()))
        assertNull(ChatScope.decode(listOf("20_Projects/README.md")))
        assertNull(ChatScope.decode(listOf("scope:folder:99_Private/People")))
    }

    @Test
    fun `an offered scope is taken once`() {
        val repository = ChatScopeRepository()
        repository.offer(ChatScope.note("Ideas.md")!!)
        assertEquals("Ideas.md", repository.take()?.path)
        assertNull(repository.take())
    }
}
