package cloud.wafflecommons.pixelbrainreader.ui.settings

import java.text.Normalizer
import java.util.Locale

/** A setting the search found, its texts resolved with the owner's names; [where] says where it sits. */
data class SettingsHit(val entry: SettingsEntry, val title: String, val description: String, val where: String)

/**
 * The Settings search (13.4, S6): any setting by its name, its description, its keywords, its topic
 * or its page, with the owner's names for the modes and the toolkit. Pure, so the JVM tests run it.
 */
object SettingsSearch {

    // ICU-safe (Android's regex engine): Unicode classes only, no inline flags.
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercased (Locale.ROOT), Normalizer NFD with \p{Mn} removed, every run of non-letters and non-digits one space, trimmed. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return SEPARATORS.replace(MARKS.replace(decomposed, ""), " ").trim()
    }

    /**
     * Every query word must start a word of the entry's title, description, keywords, topic or page
     * (all resolved with [context]'s names); an entry [SettingsIndex.shown] hides never matches.
     * Rank: title starts with the query 4, every word in the title 3, keywords 2, description 1,
     * topic or page only 0; then home rows before a page's entries, then index order.
     * [SettingsHit.where]: the topic for a home row, "<page title> · <topic>" for a page's entry.
     */
    fun search(query: String, context: SettingsContext, entries: List<SettingsEntry> = SettingsIndex.ENTRIES): List<SettingsHit> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        val queryWords = needle.split(' ')
        val appearance = context.appearance
        return entries.withIndex()
            .filter { (_, entry) -> SettingsIndex.shown(entry, context) }
            .mapNotNull { (index, entry) ->
                val title = SettingsIndex.text(entry.title, appearance)
                val description = SettingsIndex.text(entry.description, appearance)
                val topic = entry.topic.titleFor(appearance)
                val page = entry.page?.titleFor(appearance)
                val titleWords = wordsOf(title)
                val keywordWords = entry.keywords.flatMap(::wordsOf)
                val descriptionWords = wordsOf(description)
                val placeWords = wordsOf(topic) + page?.let(::wordsOf).orEmpty()
                val rank = when {
                    !covers(queryWords, titleWords + keywordWords + descriptionWords + placeWords) -> return@mapNotNull null
                    normalize(title).startsWith(needle) -> 4
                    covers(queryWords, titleWords) -> 3
                    covers(queryWords, titleWords + keywordWords) -> 2
                    covers(queryWords, titleWords + keywordWords + descriptionWords) -> 1
                    else -> 0
                }
                Ranked(rank, entry.onHome, index, SettingsHit(entry, title, description, whereOf(entry, topic, page)))
            }
            .sortedWith(compareByDescending<Ranked> { it.rank }.thenByDescending { it.onHome }.thenBy { it.index })
            .map { it.hit }
    }

    private class Ranked(val rank: Int, val onHome: Boolean, val index: Int, val hit: SettingsHit)

    private fun wordsOf(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Each query word starts one of [words]. */
    private fun covers(queryWords: List<String>, words: List<String>): Boolean =
        queryWords.all { query -> words.any { it.startsWith(query) } }

    /** A home row: its topic. A page's row: its page, then its topic (one name when they are the same, the Modes hub's). */
    private fun whereOf(entry: SettingsEntry, topic: String, page: String?): String =
        if (entry.onHome || page == null) topic else listOf(page, topic).distinct().joinToString(" · ")
}
