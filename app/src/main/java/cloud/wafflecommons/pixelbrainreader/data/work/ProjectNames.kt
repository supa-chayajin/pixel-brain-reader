package cloud.wafflecommons.pixelbrainreader.data.work

/**
 * A project's name as people say it: "21_Garden_Planner" → "Garden Planner" (the folder's
 * Johnny-Decimal number and its underscores go). A README title keeps its own spelling, minus the
 * underscores; a name that would be left empty stays as it was.
 */
fun projectDisplayName(title: String): String {
    val number = folderNumber(title)
    val rest = if (number != null) title.drop(number.length + 1) else title
    return rest.replace('_', ' ').trim().ifEmpty { title }
}

/** The badge: the folder's number ("21"), else the initials of the name ("GP"), else "?". */
fun projectBadgeText(folder: String, title: String): String =
    folderNumber(folder) ?: projectDisplayName(title)
        .split(' ', '-')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** The leading number of "21_Garden_Planner" (digits, then "_", "-" or a space), or null. */
private fun folderNumber(name: String): String? {
    val digits = name.takeWhile { it in '0'..'9' }
    return digits.takeIf { it.isNotEmpty() && name.length > it.length && name[it.length] in "_- " }
}
