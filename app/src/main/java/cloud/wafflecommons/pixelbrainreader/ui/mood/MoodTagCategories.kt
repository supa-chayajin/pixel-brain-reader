package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory

/**
 * How the mood tag categories show (12.19, owner's M1–M4): their order, titles and icons, a tag's
 * category, and each card's colors, taken from the theme so light and dark both work.
 */
object MoodTagCategories {

    /** The order the check-in sheet and the Mood Tags page show them in. */
    val ORDER: List<MoodTagCategory> = listOf(MoodTagCategory.ACTIVITIES, MoodTagCategory.PEOPLE, MoodTagCategory.PLACES)

    fun title(category: MoodTagCategory): String = when (category) {
        MoodTagCategory.ACTIVITIES -> "Activities"
        MoodTagCategory.PEOPLE -> "People"
        MoodTagCategory.PLACES -> "Places"
    }

    fun icon(category: MoodTagCategory): ImageVector = when (category) {
        MoodTagCategory.ACTIVITIES -> Icons.Outlined.Interests
        MoodTagCategory.PEOPLE -> Icons.Outlined.Groups
        MoodTagCategory.PLACES -> Icons.Outlined.Place
    }

    /** The guess for an icon (M3): what its picker group suggests; Activities for an icon this version doesn't know. */
    fun guess(iconKey: String?): MoodTagCategory = MoodTagIcons.groupOf(iconKey)?.suggests ?: MoodTagCategory.ACTIVITIES

    /** A tag's category: the one chosen, else the guess from the icon it shows. */
    fun of(tag: MoodTag): MoodTagCategory = MoodTagCategory.of(tag.category) ?: guess(MoodTagIcons.resolve(tag).key)

    /** [tags] by category, in [ORDER], each keeping the list's own order; a category with no tag is listed empty. */
    fun grouped(tags: List<MoodTag>): List<Pair<MoodTagCategory, List<MoodTag>>> {
        val byCategory = tags.groupBy(::of)
        return ORDER.map { it to byCategory[it].orEmpty() }
    }

    /**
     * Up or down within its category: the tag's index in the whole list and its neighbour's in the
     * same category, for `MoodTagRepository.moveTag`; null at that end of the category.
     */
    fun moveWithinCategory(tags: List<MoodTag>, name: String, up: Boolean): Pair<Int, Int>? {
        val from = tags.indexOfFirst { it.name == name }
        if (from < 0) return null
        val category = of(tags[from])
        val candidates = if (up) (from - 1 downTo 0) else (from + 1..tags.lastIndex)
        val to = candidates.firstOrNull { of(tags[it]) == category } ?: return null
        return from to to
    }

    /** A category card's colors: its container, what sits on it, and the strong color of a picked chip. */
    data class CardColors(val container: Color, val content: Color, val strong: Color, val onStrong: Color)

    fun colors(category: MoodTagCategory, scheme: ColorScheme): CardColors = when (category) {
        MoodTagCategory.ACTIVITIES -> CardColors(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary, scheme.onTertiary)
        MoodTagCategory.PEOPLE -> CardColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary, scheme.onSecondary)
        MoodTagCategory.PLACES -> CardColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary, scheme.onPrimary)
    }
}
