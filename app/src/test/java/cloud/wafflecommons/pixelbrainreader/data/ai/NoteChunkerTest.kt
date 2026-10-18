package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChunkerTest {

    private val path = "40_Resources/Runbooks/deploy.md"

    @Test
    fun `a fenced code block is its own chunk, labelled with its language`() {
        val note = """
            # Deploy
            Build the image first. Then push it.
            ```bash
            # build
            docker build -t app .
            docker push app
            ```
            Check the logs afterwards.
        """.trimIndent()

        val chunks = NoteChunker.chunk(note, path)

        assertEquals(3, chunks.size)
        assertEquals("[Note: deploy (40_Resources/Runbooks) > Deploy]\nBuild the image first. Then push it.", chunks[0])
        assertEquals(
            "[Note: deploy (40_Resources/Runbooks) > Deploy] [code: bash]\n# build\ndocker build -t app .\ndocker push app",
            chunks[1]
        )
        assertEquals("[Note: deploy (40_Resources/Runbooks) > Deploy]\nCheck the logs afterwards.", chunks[2])
    }

    @Test
    fun `a comment inside a fence is not a heading, a tag line is not either`() {
        val note = "# Setup\n#idea #later\n```python\n# not a heading\nprint(1)\n```\n## Next step\nDone."
        val chunks = NoteChunker.chunk(note, path)

        assertTrue(chunks.any { it.startsWith("[Note: deploy (40_Resources/Runbooks) > Setup]\n#idea #later") })
        assertTrue(chunks.any { it.startsWith("[Note: deploy (40_Resources/Runbooks) > Setup] [code: python]\n# not a heading") })
        assertTrue(chunks.any { it.startsWith("[Note: deploy (40_Resources/Runbooks) > Next step]\nDone.") })
        assertFalse(chunks.any { it.contains("> not a heading]") })
    }

    @Test
    fun `long code is cut between lines, never inside one`() {
        val lines = (1..60).map { "val value$it = compute($it)" }
        val note = "```kotlin\n" + lines.joinToString("\n") + "\n```"

        val chunks = NoteChunker.chunk(note, path)

        assertTrue(chunks.size > 1)
        val body = chunks.joinToString("\n") { it.substringAfter("\n") }
        assertEquals(lines.joinToString("\n"), body)
        chunks.forEach { assertTrue(it.contains("[code: kotlin]")) }
    }

    @Test
    fun `a fence without a language is plain code, an unclosed fence runs to the end`() {
        val chunks = NoteChunker.chunk("Intro.\n~~~\nraw text\nmore", path)
        assertEquals("[Note: deploy (40_Resources/Runbooks) > Général] [code: code]\nraw text\nmore", chunks.last())
    }

    @Test
    fun `prose packs sentences under the size limit`() {
        val sentence = "This sentence is about forty characters. "
        val chunks = NoteChunker.chunk(sentence.repeat(30), path)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= NoteChunker.TARGET_SIZE) }
    }

    @Test
    fun `an encrypted note keeps its plain title`() {
        val chunks = NoteChunker.chunk("Hello.", "99_Private/diary.md.enc")
        assertEquals("[Note: diary (99_Private) > Général]\nHello.", chunks.single())
    }
}
