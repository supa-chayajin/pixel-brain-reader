package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.work.ErrorLookup.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C8: past occurrences in order, the prompt within Nano's budget, where a bug note belongs. */
class ErrorLookupTest {

    private val trace = ErrorTrace.parse("TypeError: Envelope::\$attempts::set(): Argument #1 must be of type int\n#0 /src/Worker.php(88): x()")!!

    @Test
    fun `the error's own words come first, fused across queries, then the meaning, each note once`() {
        val byType = listOf(Hit("20_Projects/a.md", "a"), Hit("20_Projects/b.md", "b"))
        val byWords = listOf(Hit("20_Projects/b.md", "b"), Hit("30_Areas/c.md", "c"), Hit(ErrorLookup.GOLDEN_SET_PATH, "q"))
        val byMeaning = listOf(Hit("30_Areas/c.md", "c"), Hit("40_Resources/d.md", "d"))
        val merged = ErrorLookup.merge(listOf(byType, byWords), byMeaning)
        assertEquals(listOf("20_Projects/b.md", "20_Projects/a.md", "30_Areas/c.md", "40_Resources/d.md"), merged.map { it.path })
        assertEquals(listOf(true, true, true, false), merged.map { it.viaWords })
    }

    @Test
    fun `no more than five occurrences`() {
        val many = (1..9).map { Hit("20_Projects/n$it.md", "x") }
        assertEquals(ErrorLookup.MAX_OCCURRENCES, ErrorLookup.merge(listOf(many), emptyList()).size)
    }

    @Test
    fun `the grounded prompt is French, quotes the notes and stays within budget`() {
        val longTrace = (1..400).joinToString("\n") { "#$it /src/Very/Long/Path/Module$it.php($it): call$it()" }
        val occurrences = (1..5).map { ErrorLookup.Occurrence("20_Projects/n$it.md", "x".repeat(900), viaWords = true) }
        val prompt = ErrorLookup.prompt(trace, longTrace, occurrences, generalKnowledge = false)
        assertTrue(prompt.contains("Ce que ses notes en disent"))
        assertTrue(prompt.contains("[…]"))
        assertTrue(prompt.endsWith(AiLanguage.DIRECTIVE))
        assertTrue("prompt is ${prompt.length} chars", prompt.length <= 3_300)
    }

    @Test
    fun `asked anyway, the prompt says the answer comes from general knowledge`() {
        val prompt = ErrorLookup.prompt(trace, trace.headline, emptyList(), generalKnowledge = true)
        assertTrue(prompt.contains("connaissances générales"))
        assertFalse(prompt.contains("Ce que ses notes en disent"))
        assertTrue(prompt.endsWith(AiLanguage.DIRECTIVE))
    }

    @Test
    fun `the middle of a long text is cut, its head and tail kept`() {
        val cut = ErrorLookup.cutMiddle("A".repeat(50) + "B".repeat(1_000) + "C".repeat(50), 120)
        assertTrue(cut.startsWith("AAAA") && cut.endsWith("CCCC") && cut.contains("[…]"))
        assertTrue(cut.length <= 120)
    }

    @Test
    fun `a project the error names, only when one alone matches`() {
        val roots = listOf("20_Projects/21_Garden_Planner", "20_Projects/23_Photo_Sorter", "20_Projects/26_Weather_Gateway")
        assertEquals("20_Projects/21_Garden_Planner", ErrorLookup.projectNamed(roots, "at /home/me/git/garden-app/src/Bed.kt:12"))
        assertNull(ErrorLookup.projectNamed(roots, "/srv/garden/photo/x.py")) // two projects named
        assertNull(ErrorLookup.projectNamed(roots, "/srv/gateway/x.go")) // "gateway" is too common to count
        assertNull(ErrorLookup.projectNamed(roots, "nothing here"))
    }
}
