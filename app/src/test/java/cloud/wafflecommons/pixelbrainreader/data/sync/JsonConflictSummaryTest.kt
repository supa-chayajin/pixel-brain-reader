package cloud.wafflecommons.pixelbrainreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonConflictSummaryTest {

    @Test
    fun `array entries only on one side are counted, an entry that changed counts on both`() {
        val mine = """{"logs":[{"d":"2026-11-10","v":1},{"d":"2026-11-11","v":1},{"d":"2026-11-12","v":1}]}"""
        val theirs = """{"logs":[{"d":"2026-11-10","v":1},{"d":"2026-11-11","v":0}]}"""
        assertEquals("2 entries only on this phone, 1 entry only on GitHub.", JsonConflictSummary.of(mine, theirs))
    }

    @Test
    fun `object keys only on one side, and changed values`() {
        val mine = """{"level":9,"xp":120.5,"class":"BARD","phone":true}"""
        val theirs = """{"level":9,"xp":80.0,"class":"CLERIC","desktop":true}"""
        assertEquals("1 entry only on this phone, 1 entry only on GitHub, 2 entries changed.", JsonConflictSummary.of(mine, theirs))
    }

    @Test
    fun `the same data written differently, a deleted side, an unreadable copy`() {
        assertEquals("The same data, written differently.", JsonConflictSummary.of("""{"a":[1,2]}""", "{ \"a\" : [ 1, 2 ] }\n"))
        assertEquals("Deleted on this phone, kept on GitHub.", JsonConflictSummary.of(null, "{}"))
        assertEquals("Deleted on GitHub, kept on this phone.", JsonConflictSummary.of("{}", null))
        assertEquals("This phone's copy isn't valid JSON.", JsonConflictSummary.of("{oops", "{}"))
        assertEquals("GitHub's copy isn't valid JSON.", JsonConflictSummary.of("{}", "<<<<<<<"))
        assertEquals("GitHub's copy isn't valid JSON.", JsonConflictSummary.of("""{"a":1}""", """{"a":oops}"""))
        assertEquals("1 entry changed.", JsonConflictSummary.of("""{"a":true,"b":null,"c":-1.5e3}""", """{"a":false,"b":null,"c":-1.5e3}"""))
    }
}
