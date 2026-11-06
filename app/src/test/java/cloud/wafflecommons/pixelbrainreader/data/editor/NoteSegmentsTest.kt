package cloud.wafflecommons.pixelbrainreader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's segments for long notes: a section per heading, tables apart, code never split. */
class NoteSegmentsTest {

    private val rfc = """
        Intro paragraph.

        # RFC 12: the cache

        ## Context

        Text with a | pipe in prose.

        | Option | Latency | Cost | Risk | Owner |
        |:--|--:|:-:|---|---|
        | Redis | 1 ms | low | ok | Lead |
        | None | 9 ms | none | ok | — |

        After the table.

        ```bash
        # not a heading
        | a | b |
        |---|---|
        ```

        ### Decision

        #### A deep heading stays inside
        Done.
    """.trimIndent()

    @Test
    fun `a note splits at headings and around tables, never inside code`() {
        val s = NoteSegments.split(rfc)
        assertEquals(6, s.size)
        assertEquals(NoteSegment.Markdown("Intro paragraph."), s[0])
        assertEquals(NoteHeading(1, "RFC 12: the cache"), (s[1] as NoteSegment.Markdown).heading)
        val context = s[2] as NoteSegment.Markdown
        assertEquals(NoteHeading(2, "Context"), context.heading)
        assertTrue(context.text.endsWith("Text with a | pipe in prose."))
        val table = s[3] as NoteSegment.Table
        assertEquals(5, table.columns)
        assertEquals(4, table.markdown.lines().size)
        val after = s[4] as NoteSegment.Markdown
        assertNull(after.heading)
        assertTrue(after.text.contains("# not a heading") && after.text.contains("|---|---|"))
        val decision = s[5] as NoteSegment.Markdown
        assertEquals(NoteHeading(3, "Decision"), decision.heading)
        assertTrue(decision.text.contains("#### A deep heading stays inside"))
    }

    @Test
    fun `the outline lists the headings with the segment they start`() {
        val outline = NoteSegments.outline(NoteSegments.split(rfc))
        assertEquals(listOf(1 to "RFC 12: the cache", 2 to "Context", 5 to "Decision"), outline.map { it.first to it.second.text })
    }

    @Test
    fun `headings and delimiter rows follow the markdown rules`() {
        assertEquals(NoteHeading(1, "C#"), NoteSegments.headingOf("# C#"))
        assertEquals(NoteHeading(2, "Title"), NoteSegments.headingOf("## Title ##"))
        assertNull(NoteSegments.headingOf("#hashtag"))
        assertNull(NoteSegments.headingOf("####### seven"))
        assertEquals(2, NoteSegments.delimiterColumns("| --- | :-: |"))
        assertEquals(2, NoteSegments.delimiterColumns("--- | ---"))
        assertNull(NoteSegments.delimiterColumns("---"))
        assertNull(NoteSegments.delimiterColumns("| a | b |"))
        // A thematic break under a line with a pipe is not a table.
        assertEquals(1, NoteSegments.split("a | b\n\n---\nc").size)
    }

    @Test
    fun `a closed mermaid fence becomes a diagram, other fences stay code`() {
        val note = "Intro\n\n```mermaid\nflowchart LR\n  A --> B\n```\n\n```js\nconst a = 1\n```\n\n~~~Mermaid theme=dark\nsequenceDiagram\n~~~\n"
        val s = NoteSegments.split(note)
        assertEquals(NoteSegment.Mermaid("flowchart LR\n  A --> B"), s[1])
        assertTrue((s[2] as NoteSegment.Markdown).text.contains("```js"))
        assertEquals(NoteSegment.Mermaid("sequenceDiagram"), s[3])
        // An unclosed mermaid fence stays text: nothing to draw yet.
        assertTrue(NoteSegments.split("```mermaid\ngraph TD").single() is NoteSegment.Markdown)
    }

    @Test
    fun `an unclosed fence keeps the rest of the note in one piece`() {
        val s = NoteSegments.split("Before\n```\n## inside\n| a | b |\n|---|---|")
        assertEquals(1, s.size)
    }
}
