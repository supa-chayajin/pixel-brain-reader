package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RPG rules (13.4, S-f): a tag rule names its stat, never its three-letter code. */
class StatNamesTest {

    @Test
    fun `each stat has its own name`() {
        val names = Attribute.entries.map { it.statName() }
        assertEquals(Attribute.entries.size, names.distinct().size)
        assertTrue(names.none { it.isBlank() || it == it.uppercase() })
        assertEquals(
            listOf("Vigor", "Focus", "Endurance", "Social", "Creativity", "Intellect", "Spirit"),
            listOf(Attribute.VIG, Attribute.MND, Attribute.END, Attribute.SOC, Attribute.CRE, Attribute.INT, Attribute.FTH).map { it.statName() }
        )
    }
}
