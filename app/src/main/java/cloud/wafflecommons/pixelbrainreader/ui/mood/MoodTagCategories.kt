package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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

    /**
     * Activities and Places take the theme's tertiary and primary tones. People has a warm pink of its
     * own: a dynamic theme's secondary palette is always muted, and the owner wanted a colorful sheet
     * (M5). Its text stays above 4.5:1 in light and dark.
     */
    fun colors(category: MoodTagCategory, scheme: ColorScheme): CardColors {
        val dark = scheme.surface.luminance() < 0.5f
        return when (category) {
            MoodTagCategory.ACTIVITIES -> themed(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary, scheme.onTertiary, scheme, dark)
            MoodTagCategory.PEOPLE -> if (dark) PEOPLE_DARK else PEOPLE_LIGHT
            MoodTagCategory.PLACES -> themed(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary, scheme.onPrimary, scheme, dark)
        }
    }

    /**
     * A card in one of the theme's tones. A dynamic dark scheme can hand out a light container (the
     * API 37 emulator's tertiary one, on 2026-11-15): in a dark theme such a container gives way to
     * the accent blended into the surface, under the surface's own text, so every card stays dark.
     */
    private fun themed(container: Color, onContainer: Color, strong: Color, onStrong: Color, scheme: ColorScheme, dark: Boolean): CardColors =
        if (dark && container.luminance() > 0.4f) {
            CardColors(lerp(scheme.surface, strong, 0.35f), scheme.onSurface, strong, onStrong)
        } else {
            CardColors(container, onContainer, strong, onStrong)
        }

    private val PEOPLE_LIGHT = CardColors(Color(0xFFFFD9E2), Color(0xFF3E001D), Color(0xFF9C4068), Color(0xFFFFFFFF))
    private val PEOPLE_DARK = CardColors(Color(0xFF7D2950), Color(0xFFFFD9E2), Color(0xFFFFB1C8), Color(0xFF5E1133))
}
