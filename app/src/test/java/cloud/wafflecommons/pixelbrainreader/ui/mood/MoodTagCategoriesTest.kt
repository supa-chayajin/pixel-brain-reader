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

    /** What MoodTagRepository.moveTag does with the pair: out at the first index, in at the second. */
    private fun List<MoodTag>.moved(move: Pair<Int, Int>?): List<MoodTag> =
        move?.let { (from, to) -> toMutableList().apply { add(to, removeAt(from)) } } ?: this

    private val mixed = listOf(
        MoodTag("Gaming", category = "activities"),
        MoodTag("Kid", category = "people"),
        MoodTag("Coding", category = "activities"),
        MoodTag("Home", category = "places"),
        MoodTag("Reading", category = "activities")
    )

    private fun inCategory(tags: List<MoodTag>, category: MoodTagCategory) =
        MoodTagCategories.grouped(tags).first { it.first == category }.second.map { it.name }

    @Test
    fun `moveWithinCategoryTo moves a tag among its category only`() {
        // Reading to the top of Activities, Coding to the middle of it.
        val first = mixed.moved(MoodTagCategories.moveWithinCategoryTo(mixed, "Reading", 0))
        assertEquals(listOf("Reading", "Gaming", "Coding"), inCategory(first, MoodTagCategory.ACTIVITIES))
        val second = mixed.moved(MoodTagCategories.moveWithinCategoryTo(mixed, "Gaming", 1))
        assertEquals(listOf("Coding", "Gaming", "Reading"), inCategory(second, MoodTagCategory.ACTIVITIES))
        // The other categories keep their tags, in their order.
        listOf(first, second).forEach { moved ->
            assertEquals(listOf("Kid"), inCategory(moved, MoodTagCategory.PEOPLE))
            assertEquals(listOf("Home"), inCategory(moved, MoodTagCategory.PLACES))
        }
    }

    @Test
    fun `a target past the category's end lands last`() {
        val moved = mixed.moved(MoodTagCategories.moveWithinCategoryTo(mixed, "Gaming", 9))
        assertEquals(listOf("Coding", "Reading", "Gaming"), inCategory(moved, MoodTagCategory.ACTIVITIES))
    }

    @Test
    fun `no move gives null`() {
        assertNull(MoodTagCategories.moveWithinCategoryTo(mixed, "Coding", 1))
        assertNull(MoodTagCategories.moveWithinCategoryTo(mixed, "Kid", 3))
        assertNull(MoodTagCategories.moveWithinCategoryTo(mixed, "Nobody", 0))
    }
}
