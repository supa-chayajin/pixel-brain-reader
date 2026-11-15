package cloud.wafflecommons.pixelbrainreader.data.repository

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Spaced echoes (K6, 12.20; the owner's D11): the notes you choose to keep in mind come back on a
 * widening schedule, SM-2-lite. Your answer moves the next return: Again tomorrow, Good after the
 * interval times the note's ease, Easy further still. The schedule is kept in the vault. Pure.
 */
object SpacedReview {

    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_INTERVAL_DAYS = 365

    /** Notes never seen before that may start on one day: a large folder comes in slowly. */
    const val NEW_PER_DAY = 3

    /** A note's schedule: days until the next return, its ease, and the day it is due (epoch day). */
    @Serializable
    data class Card(val interval: Int, val ease: Double, val due: Long)

    enum class Answer { AGAIN, GOOD, EASY }

    fun next(card: Card?, answer: Answer, today: Long): Card {
        val interval = card?.interval ?: 0
        val ease = card?.ease ?: START_EASE
        return when (answer) {
            Answer.AGAIN -> Card(interval = 1, ease = max(MIN_EASE, ease - 0.2), due = today + 1)
            Answer.GOOD -> {
                val next = if (interval == 0) 1 else min(MAX_INTERVAL_DAYS, max(interval + 1, (interval * ease).roundToInt()))
                Card(interval = next, ease = ease, due = today + next)
            }
            Answer.EASY -> {
                val next = if (interval == 0) 4 else min(MAX_INTERVAL_DAYS, max(interval + 2, (interval * ease * 1.3).roundToInt()))
                Card(interval = next, ease = ease + 0.15, due = today + next)
            }
        }
    }

    /**
     * The notes to see [today]: the scheduled ones due (most overdue first), then up to
     * [NEW_PER_DAY] never seen, picked by a shuffle seeded by the day; at most [limit] in all.
     */
    fun due(inScope: List<String>, cards: Map<String, Card>, today: Long, limit: Int): List<String> {
        val scheduled = inScope.mapNotNull { path -> cards[path]?.takeIf { it.due <= today }?.let { path to it.due } }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
        val fresh = inScope.filter { it !in cards }.sorted().shuffled(Random(today)).take(NEW_PER_DAY)
        return (scheduled + fresh).take(limit)
    }

    private val REVIEW_TAG = Regex("(?<![\\p{L}\\p{N}_/#-])#review(?![\\p{L}\\p{N}_-])")
    private val TAGS_LINE = Regex("^tags:\\s*\\[(.*)\\]\\s*$")
    private val TAG_ITEM = Regex("^\\s*-\\s*['\"]?#?review['\"]?\\s*$")

    /** Whether a note asks to come back: `#review` in its text, or `review` among its frontmatter tags. */
    fun tagged(content: String): Boolean {
        if (REVIEW_TAG.containsMatchIn(content)) return true
        if (!content.startsWith("---")) return false
        val end = content.indexOf("\n---", startIndex = 3)
        if (end < 0) return false
        val lines = content.substring(3, end).lines()
        var inTags = false
        for (line in lines) {
            TAGS_LINE.find(line.trim())?.let { match ->
                return match.groupValues[1].split(',').any { it.trim().trim('\'', '"').removePrefix("#") == "review" }
            }
            when {
                line.trim() == "tags:" -> inTags = true
                inTags && TAG_ITEM.matches(line) -> return true
                inTags && !line.trimStart().startsWith("-") -> inTags = false
            }
        }
        return false
    }

    /** Whether [path] is under one of the [folders] picked for review. */
    fun inFolders(path: String, folders: Set<String>): Boolean = folders.any { path.startsWith(it.trimEnd('/') + "/") }
}
