package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * A vault folder named the Johnny-Decimal way ("20_Projects", "21_Waffle_Framework", "99_Private").
 * [label] is the AREA the code belongs to (20–29 → Projects), so a sub-category folder can show
 * where it lives.
 */
data class JohnnyDecimalCategory(
    val code: Int,
    val label: String,
    val isPrivate: Boolean = false
) {
    /**
     * Area folders (x0, 00–09) already say what they are, and 90–99 holds system folders: a badge
     * only adds information on a sub-category ("21_…" → Projects) or a private folder.
     */
    val isWorthABadge: Boolean
        get() = isPrivate || (code % 10 != 0 && code in 10..89)
}

fun parseJohnnyDecimal(name: String): JohnnyDecimalCategory? {
    val prefix = name.takeWhile { it.isDigit() }
    if (prefix.isEmpty() || prefix.length > 2) return null
    val code = prefix.toInt()
    val label = when (code) {
        in 0..9 -> "Meta"
        in 10..19 -> "Journal"
        in 20..29 -> "Projects"
        in 30..39 -> "Areas"
        in 40..49 -> "Resources"
        in 50..89 -> "Archives"
        else -> "System"
    }
    // Only a folder that says it is private is private — "99_System" is not.
    val isPrivate = name.contains("private", ignoreCase = true)
    return JohnnyDecimalCategory(code, if (isPrivate) "Private" else label, isPrivate)
}
