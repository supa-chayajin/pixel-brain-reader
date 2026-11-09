package cloud.wafflecommons.pixelbrainreader.ui.mood

import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTagIconsTest {

    @Test
    fun `every icon has its own key, saved as a plain material name`() {
        val keys = MoodTagIcons.ALL.map { it.key }
        assertEquals("duplicate keys: ${keys.groupingBy { it }.eachCount().filterValues { it > 1 }}", keys.size, keys.toSet().size)
        keys.forEach { assertTrue("key $it", it.matches(Regex("[a-z]+(_[a-z]+)*"))) }
        assertTrue(MoodTagIcons.ALL.none { it.label.isBlank() })
        assertTrue("a picker worth opening", MoodTagIcons.ALL.size >= 40)
    }

    @Test
    fun `the seed tags keep the glyphs they always had`() {
        val expected = mapOf(
            "Coding" to "code", "Working" to "work_history", "Gaming" to "sports_esports",
            "Chilling" to "beach_access", "Solo" to "person", "Family" to "family_restroom",
            "Friends" to "groups", "Home" to "home", "Work" to "home_work", "CDS" to "work",
            "Out" to "nature_people"
        )
        assertEquals(MoodTagRepository.DEFAULT_TAGS.toSet(), expected.keys)
        expected.forEach { (tag, key) ->
            assertEquals(tag, key, MoodTagIcons.defaultKeyFor(tag))
            assertNotNull("$key is in the picker", MoodTagIcons.forKey(key))
        }
    }

    @Test
    fun `a picked icon wins, an unknown one falls back to the tag's default`() {
        assertEquals("piano", MoodTagIcons.resolve(MoodTag("Gaming", "piano")).key)
        assertEquals("sports_esports", MoodTagIcons.resolve(MoodTag("Gaming", "a_newer_icon")).key)
        assertEquals("sports_esports", MoodTagIcons.resolve(MoodTag("Gaming")).key)
        assertSame(MoodTagIcons.GENERIC, MoodTagIcons.resolve(MoodTag("Knitting")))
        assertEquals(MoodTagIcons.DEFAULT_KEY, MoodTagIcons.GENERIC.key)
        assertNull(MoodTagIcons.forKey(null))
    }

    @Test
    fun `a past entry's activity shows its tag's icon, or its default once the tag is gone`() {
        val tags = listOf(MoodTag("Gaming", "piano"), MoodTag("Reading", "menu_book"))
        assertEquals("piano", MoodTagIcons.resolve("Gaming", tags).key)
        assertEquals("code", MoodTagIcons.resolve("Coding", tags).key)
        assertSame(MoodTagIcons.GENERIC, MoodTagIcons.resolve("Knitting", tags))
    }
}
