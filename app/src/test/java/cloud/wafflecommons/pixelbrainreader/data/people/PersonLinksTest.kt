package cloud.wafflecommons.pixelbrainreader.data.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The meetings linked to a person by hand (12.19, the owner's D5), on the phone only. */
class PersonLinksTest {

    @Test
    fun `a recurring meeting's instances share their series, a single event is its own`() {
        assertEquals("7cbh8rpc10lrc0ckih9tafss99", MeetingSeries.keyOf("7cbh8rpc10lrc0ckih9tafss99_20261116T080000Z"))
        assertEquals("7cbh8rpc10lrc0ckih9tafss99", MeetingSeries.keyOf("7cbh8rpc10lrc0ckih9tafss99_20261123T080000Z"))
        assertEquals("abc123def", MeetingSeries.keyOf("abc123def"))
        assertEquals("abc123def", MeetingSeries.keyOf("abc123def_20261116"))
    }

    @Test
    fun `a link made on one week's meeting finds the next week's`() {
        val stored = PersonLinks.link(null, MeetingSeries.keyOf("series1_20261116T080000Z"), "1a2b3c4d", PersonKind.WORK)

        val link = PersonLinks.of(PersonLinks.decode(stored), "series1_20261123T080000Z")

        assertEquals(PersonLink("1a2b3c4d", "pro"), link)
        assertNull(PersonLinks.of(PersonLinks.decode(stored), "series2_20261123T080000Z"))
    }

    @Test
    fun `unlinking, and forgetting a deleted person, keep the other links`() {
        var stored = PersonLinks.link(null, "series1", "1a2b3c4d", PersonKind.WORK)
        stored = PersonLinks.link(stored, "series2", "1a2b3c4d", PersonKind.WORK)
        stored = PersonLinks.link(stored, "series3", "99999999", PersonKind.PERSONAL)

        assertEquals(setOf("series2", "series3"), PersonLinks.decode(PersonLinks.unlink(stored, "series1")).keys)
        assertEquals(setOf("series3"), PersonLinks.decode(PersonLinks.forget(stored, "1a2b3c4d")).keys)
    }

    @Test
    fun `nothing stored, or a value that doesn't parse, is no link at all`() {
        assertTrue(PersonLinks.decode(null).isEmpty())
        assertTrue(PersonLinks.decode("").isEmpty())
        assertTrue(PersonLinks.decode("{not json").isEmpty())
    }

    @Test
    fun `the stored value holds neutral ids only`() {
        val stored = PersonLinks.link(null, "series1", "1a2b3c4d", PersonKind.PERSONAL)

        assertEquals("""{"series1":{"person":"1a2b3c4d","kind":"perso"}}""", stored)
    }
}
