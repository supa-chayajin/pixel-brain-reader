package cloud.wafflecommons.pixelbrainreader.data.capture

import cloud.wafflecommons.pixelbrainreader.data.capture.CheckInRouting.Destination
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class CheckInRoutingTest {

    @Test
    fun `a plain line is a moment of the day, not a scrap`() {
        val r = CheckInRouting.route("Belle balade au bord du lac")!!
        assertEquals(Destination.MOMENT, r.destination)
        assertEquals("Belle balade au bord du lac", r.text)
    }

    @Test
    fun `a written time stays on the moment`() {
        assertEquals(LocalTime.of(18, 0), CheckInRouting.route("18h apéro avec Sam")!!.guess.time)
    }

    @Test
    fun `gratitude by words or by the Thankful field`() {
        assertEquals(Destination.GRATITUDE, CheckInRouting.route("Merci pour le dîner")!!.destination)
        val forced = CheckInRouting.route("le soleil", forceGratitude = true)!!
        assertEquals(Destination.GRATITUDE, forced.destination)
        assertEquals("le soleil", forced.text)
    }

    @Test
    fun `a to-do at night is for tomorrow, an idea goes to the scratchpad`() {
        assertEquals(Destination.TASK_TOMORROW, CheckInRouting.route("Appeler le garage à 9h")!!.destination)
        val idea = CheckInRouting.route("Idée : un carnet de voyage")!!
        assertEquals(Destination.SCRAP, idea.destination)
        assertEquals("💡 un carnet de voyage", idea.text)
    }

    @Test
    fun `an empty reply does nothing`() {
        assertNull(CheckInRouting.route("   "))
        assertNull(CheckInRouting.route("", forceGratitude = true))
    }

    @Test
    fun `mood buttons use scores and labels the app already stores`() {
        assertEquals(listOf(4, 3, 2), CheckInRouting.MOODS.map { it.first })
        assertEquals(listOf("Good", "Okay", "Bad"), CheckInRouting.MOODS.map { it.second })
    }

    @Test
    fun `mood buttons wear the faces of the device's emoji set`() {
        assertEquals(
            listOf("🙂 Good", "😐 Okay", "😞 Hard"),
            CheckInRouting.MOODS.map { CheckInRouting.buttonLabel(it, MoodEmojiSets.CLASSIC) }
        )
        assertEquals(
            listOf("🌖 Good", "🌗 Okay", "🌘 Hard"),
            CheckInRouting.MOODS.map { CheckInRouting.buttonLabel(it, MoodEmojiSets.byId("moon")) }
        )
    }
}
