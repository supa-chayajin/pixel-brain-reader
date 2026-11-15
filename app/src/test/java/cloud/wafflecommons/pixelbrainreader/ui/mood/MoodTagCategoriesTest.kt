package cloud.wafflecommons.pixelbrainreader.ui.mood

import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mood tag categories (12.19, owner's M1–M3): the guess from an icon, the grouping, moving within one. */
class MoodTagCategoriesTest {

    @Test
    fun `an icon suggests its picker group's category, Activities otherwise`() {
        assertEquals(MoodTagCategory.PEOPLE, MoodTagCategories.guess("child_care"))
        assertEquals(MoodTagCategory.PLACES, MoodTagCategories.guess("home"))
        assertEquals(MoodTagCategory.ACTIVITIES, MoodTagCategories.guess("code"))
        assertEquals(MoodTagCategory.ACTIVITIES, MoodTagCategories.guess("spa"))
        assertEquals(MoodTagCategory.ACTIVITIES, MoodTagCategories.guess("an_icon_from_later"))
        assertEquals(MoodTagCategory.ACTIVITIES, MoodTagCategories.guess(null))
    }

    @Test
    fun `a chosen category wins over the guess, an unknown one falls back to it`() {
        // Chilling's beach icon would guess Places: the owner put it in Activities.
        assertEquals(MoodTagCategory.ACTIVITIES, MoodTagCategories.of(MoodTag("Chilling", category = "activities")))
        assertEquals(MoodTagCategory.PLACES, MoodTagCategories.of(MoodTag("Chilling")))
        assertEquals(MoodTagCategory.PEOPLE, MoodTagCategories.of(MoodTag("Kid", "child_care", category = "weather")))
    }

    @Test
    fun `tags are grouped in the three categories' order, each keeping its own order`() {
        val tags = listOf(
            MoodTag("Home", category = "places"),
            MoodTag("Gaming", category = "activities"),
            MoodTag("Kid", category = "people"),
            MoodTag("Coding", category = "activities")
        )

        val grouped = MoodTagCategories.grouped(tags)

        assertEquals(MoodTagCategories.ORDER, grouped.map { it.first })
        assertEquals(listOf("Gaming", "Coding"), grouped[0].second.map { it.name })
        assertEquals(listOf("Kid"), grouped[1].second.map { it.name })
        assertEquals(listOf("Home"), grouped[2].second.map { it.name })
        assertEquals(emptyList<MoodTag>(), MoodTagCategories.grouped(emptyList()).flatMap { it.second })
    }

    @Test
    fun `up and down skip the other categories' tags and stop at the ends`() {
        val tags = listOf(
            MoodTag("Gaming", category = "activities"),
            MoodTag("Kid", category = "people"),
            MoodTag("Coding", category = "activities")
        )

        assertEquals(2 to 0, MoodTagCategories.moveWithinCategory(tags, "Coding", up = true))
        assertEquals(0 to 2, MoodTagCategories.moveWithinCategory(tags, "Gaming", up = false))
        assertNull(MoodTagCategories.moveWithinCategory(tags, "Gaming", up = true))
        assertNull(MoodTagCategories.moveWithinCategory(tags, "Kid", up = false))
        assertNull(MoodTagCategories.moveWithinCategory(tags, "Nobody", up = true))
    }
}
