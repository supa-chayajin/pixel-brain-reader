package cloud.wafflecommons.pixelbrainreader.data.vault

/**
 * Inbox filing (roadmap 2.6): where an inbox note probably belongs and which tags it could take,
 * from the notes it reads like (on-device search) and the notes it links to. Pure scoring.
 */
object InboxFiling {

    /** Folders never offered as a destination. */
    fun isDestination(folder: String): Boolean =
        folder.isNotEmpty() && !folder.startsWith(VaultSchema.INBOX_FOLDER) && !folder.startsWith("10_Journal") &&
            !folder.startsWith("99_") && !folder.split('/').any { it.startsWith(".") || it.equals("Worklog", ignoreCase = true) }

    /** Destination folders by score: a linked note's folder counts twice a similar note's. */
    fun folders(similar: List<String>, linked: List<String>, limit: Int = 3): List<String> {
        val scores = mutableMapOf<String, Int>()
        similar.map { it.substringBeforeLast('/', "") }.filter(::isDestination).forEach { scores.merge(it, 1, Int::plus) }
        linked.map { it.substringBeforeLast('/', "") }.filter(::isDestination).forEach { scores.merge(it, 2, Int::plus) }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { it.key }
    }

    /** The similar notes' tags, most shared first, minus those the note has and the inbox's own. */
    fun tags(similarTags: List<List<String>>, current: List<String>, limit: Int = 6): List<String> {
        val have = current.map { it.lowercase() }.toSet() + setOf("inbox", "voice", "ai")
        return similarTags.flatten().map { it.lowercase().removePrefix("#") }.filter { it.isNotEmpty() && it !in have }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }
}
