package cloud.wafflecommons.pixelbrainreader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class EditorAssistTest {

    private val now = LocalDateTime.of(2026, 10, 18, 14, 32)

    @Test
    fun `a slash starts a command at a line start or after a space, never inside a path or a URL`() {
        assertEquals(EditorTrigger.Slash("da", 0), EditorAssist.trigger("/da", 3))
        assertEquals(EditorTrigger.Slash("", 5), EditorAssist.trigger("Text /", 6))
        assertEquals(EditorTrigger.Slash("to", 6), EditorAssist.trigger("line\n /to", 9))
        assertNull(EditorAssist.trigger("20_Projects/plan", 16))
        assertNull(EditorAssist.trigger("see https://example.com/x", 25))
        assertNull(EditorAssist.trigger("1/2", 3))
        assertNull(EditorAssist.trigger("/date now", 9)) // a space ends the command
    }

    @Test
    fun `an open wikilink on the current line triggers completion`() {
        assertEquals(EditorTrigger.Link("Pla", 4), EditorAssist.trigger("See [[Pla", 9))
        assertNull(EditorAssist.trigger("See [[Plan]] and", 16))
        assertNull(EditorAssist.trigger("See [[Plan|alias", 16))
        assertNull(EditorAssist.trigger("[[Plan\nnext", 11))
    }

    @Test
    fun `a command replaces its token and places the caret`() {
        val trigger = EditorAssist.trigger("Done /da", 8)!!
        val date = EditorAssist.matchingCommands("da", now).single()
        assertEquals(AssistEdit("Done 2026-10-18", 15), EditorAssist.apply("Done /da", 8, trigger, date))

        val code = EditorAssist.commands(now).first { it.id == "code" }
        val edit = EditorAssist.apply("/code", 5, EditorAssist.trigger("/code", 5)!!, code)
        assertEquals("```\n\n```", edit.text)
        assertEquals(4, edit.caret) // on the empty line inside the fence
    }

    @Test
    fun `completion closes the link once, even when the brackets were typed`() {
        val trigger = EditorAssist.trigger("See [[Pla", 9)!!
        assertEquals(AssistEdit("See [[Plan Q4]]", 15), EditorAssist.complete("See [[Pla", 9, trigger, "Plan Q4"))
        assertEquals(AssistEdit("See [[Plan Q4]] ok", 15), EditorAssist.complete("See [[Pla]] ok", 9, trigger, "Plan Q4"))
    }

    @Test
    fun `note names match accents and case aside, prefixes first`() {
        val names = listOf("Réunion hebdo", "Compte rendu réunion", "Roadmap", "reunion")
        assertEquals(listOf("reunion", "Réunion hebdo", "Compte rendu réunion"), EditorAssist.matchingNotes("REUN", names))
        assertEquals(listOf("todo"), EditorAssist.matchingCommands("to", now).map { it.id })
    }
}
