package cloud.wafflecommons.pixelbrainreader.data.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodEmojiSetsTest {

    @Test
    fun `every set has five different faces and its own id and name`() {
        val sets = MoodEmojiSets.ALL
        assertTrue("several sets to pick from", sets.size >= 5)
        assertEquals(sets.size, sets.map { it.id }.toSet().size)
        assertEquals(sets.size, sets.map { it.name }.toSet().size)
        sets.forEach { set ->
            assertEquals("${set.id} has five faces", MoodEmojiSet.SCORES, set.faces.size)
            assertEquals("${set.id} repeats a face", set.faces.size, set.faces.toSet().size)
            assertTrue("${set.id} has a blank face", set.faces.none { it.isBlank() })
            assertTrue("${set.id}: ids are saved, keep them plain", set.id.matches(Regex("[a-z]+")))
        }
    }

    @Test
    fun `the default is the classic set the check-in always showed`() {
        assertSame(MoodEmojiSets.CLASSIC, MoodEmojiSets.DEFAULT)
        assertEquals(listOf("😫", "😞", "😐", "🙂", "🤩"), MoodEmojiSets.DEFAULT.faces)
        assertSame(MoodEmojiSets.CLASSIC, MoodEmojiSets.ALL.first())
    }

    @Test
    fun `an id nobody saved, or one this version doesn't know, is the default`() {
        assertSame(MoodEmojiSets.DEFAULT, MoodEmojiSets.byId(null))
        assertSame(MoodEmojiSets.DEFAULT, MoodEmojiSets.byId("sparkles-from-a-newer-version"))
        MoodEmojiSets.ALL.forEach { assertSame(it, MoodEmojiSets.byId(it.id)) }
    }

    @Test
    fun `a day's average keeps the thresholds the app always used`() {
        val classic = MoodEmojiSets.CLASSIC
        val expected = listOf(
            1.0 to "😫", 1.79 to "😫",
            1.8 to "😞", 2.59 to "😞",
            2.6 to "😐", 3.39 to "😐",
            3.4 to "🙂", 4.19 to "🙂",
            4.2 to "🤩", 5.0 to "🤩",
            Double.NaN to "😐"
        )
        expected.forEach { (average, face) -> assertEquals("average $average", face, classic.forAverage(average)) }
    }

    @Test
    fun `a score picks its face, an out-of-range score the nearest one`() {
        val moon = MoodEmojiSets.byId("moon")
        assertEquals(listOf("🌑", "🌘", "🌗", "🌖", "🌕"), (1..5).map { moon.forScore(it) })
        assertEquals("🌑", moon.forScore(0))
        assertEquals("🌕", moon.forScore(9))
    }
}
