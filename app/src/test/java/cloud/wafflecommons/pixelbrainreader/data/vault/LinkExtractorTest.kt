package cloud.wafflecommons.pixelbrainreader.data.vault

import cloud.wafflecommons.pixelbrainreader.data.repository.LinkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {

    @Test
    fun `wikilinks keep their target and drop alias and heading`() {
        val links = LinkExtractor.extract("See [[Project Hub|the hub]] and [[RFC_001#Context]].")
        assertEquals(listOf("Project Hub", "RFC_001"), links.map { it.target })
        assertEquals(listOf("project hub", "rfc_001"), links.map { it.targetKey })
        assertTrue(links.all { it.kind == LinkExtractor.KIND_WIKI && it.line == 1 })
    }

    @Test
    fun `embeds and markdown links are their own kinds`() {
        val links = LinkExtractor.extract("![[diagram.png]]\n[spec](../Specs/Gateway%20Spec.md#api) [web](https://x.io/a.md)")
        assertEquals(LinkExtractor.KIND_EMBED, links[0].kind)
        assertEquals("diagram.png", links[0].targetKey)
        assertEquals(LinkExtractor.KIND_MARKDOWN, links[1].kind)
        assertEquals("../Specs/Gateway Spec.md", links[1].target)
        assertEquals("gateway spec", links[1].targetKey)
        assertEquals(2, links.size) // the https link is not a vault link
    }

    @Test
    fun `code is not scanned`() {
        val md = "```kotlin\nval x = \"[[NotALink]]\"\n```\nUse `[[inline]]` or [[Real]]"
        assertEquals(listOf("Real"), LinkExtractor.extract(md).map { it.target })
    }

    @Test
    fun `a tilde fence is closed only by a tilde fence`() {
        val md = "~~~\n```\n[[Inside]]\n~~~\n[[After]]"
        assertEquals(listOf("After"), LinkExtractor.extract(md).map { it.target })
    }

    @Test
    fun `context is the trimmed line`() {
        val link = LinkExtractor.extract("   - Decision taken with [[Team]] today   ").single()
        assertEquals("- Decision taken with [[Team]] today", link.context)
    }

    @Test
    fun `keys ignore folders case and the md extension`() {
        assertEquals("my note", LinkExtractor.keyOfPath("20_Projects/A/My Note.md"))
        assertEquals("photo.jpg", LinkExtractor.keyOf("assets/Photo.JPG"))
    }

    @Test
    fun `a folder-qualified target must match the end of the path`() {
        assertTrue(LinkRepository.pointsAt("Note", "20_Projects/A/Note.md"))
        assertTrue(LinkRepository.pointsAt("A/Note", "20_Projects/A/Note.md"))
        assertFalse(LinkRepository.pointsAt("B/Note", "20_Projects/A/Note.md"))
    }
}
