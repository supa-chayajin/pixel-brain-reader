package cloud.wafflecommons.pixelbrainreader.data.ai

import java.util.Locale

/** One question of the vault's golden set: what is asked, and the notes that should answer it. */
data class GoldenQuestion(
    val id: String,
    val kind: String,
    val language: String,
    val question: String,
    /** Vault paths; empty for a negative (the vault does not hold the answer). */
    val expected: List<String>
) {
    val isNegative: Boolean get() = expected.isEmpty()
}

/**
 * The retrieval harness's pure side (R1): reads `99_System/rag_golden_set.md`, a note the owner
 * keeps in the vault, and scores what the searches returned. Settled decision #2: the floor and
 * the ranking change only on these numbers.
 */
object GoldenSet {

    const val PATH = "99_System/rag_golden_set.md"
    const val K = 5

    private val HEADING = Regex("""^###\s+(Q[0-9]+)\s*·\s*([a-z]+)\s*·\s*([a-z]+)\s*$""")
    private val QUESTION = Regex("""^q:\s*(.+)$""")
    private val EXPECT = Regex("""^expect:\s*`([^`]+)`\s*$""")

    fun parse(content: String): List<GoldenQuestion> {
        val questions = mutableListOf<GoldenQuestion>()
        var heading: MatchResult? = null
        var question: String? = null
        val expected = mutableListOf<String>()
        fun flush() {
            val h = heading ?: return
            val q = question ?: return
            questions += GoldenQuestion(h.groupValues[1], h.groupValues[2], h.groupValues[3], q, expected.toList())
        }
        for (raw in content.lines()) {
            val line = raw.trim()
            val h = HEADING.matchEntire(line)
            if (h != null) {
                flush()
                heading = h
                question = null
                expected.clear()
                continue
            }
            QUESTION.matchEntire(line)?.let { question = it.groupValues[1].trim() }
            EXPECT.matchEntire(line)?.let { expected += it.groupValues[1].trim() }
        }
        flush()
        return questions
    }

    /** What each search brought back for a question, best first (vault paths). */
    data class Outcome(val question: GoldenQuestion, val byMeaning: List<String>, val byWords: List<String>)

    data class Report(
        val questions: Int,
        /** Mean recall@[K] over the questions with expected notes. */
        val meaningRecall: Double,
        val wordsRecall: Double,
        /** Both lists' top [K] together: the ceiling a hybrid search could reach. */
        val eitherRecall: Double,
        val negatives: Int,
        /** Negatives the vector search left empty, as the 0.35 floor should. */
        val negativesClean: Int,
        /** Questions none of whose notes came back. */
        val missed: List<String>
    ) {
        fun summary(): String = String.format(
            Locale.ROOT,
            "%d questions · recall@%d: meaning %.2f, words %.2f, either %.2f · negatives clean %d/%d%s",
            questions, K, meaningRecall, wordsRecall, eitherRecall, negativesClean, negatives,
            if (missed.isEmpty()) "" else " · missed ${missed.joinToString(", ")}"
        )
    }

    fun score(outcomes: List<Outcome>): Report {
        val positives = outcomes.filterNot { it.question.isNegative }
        fun recall(found: List<String>, expected: List<String>) = expected.count { it in found }.toDouble() / expected.size
        fun mean(values: List<Double>) = if (values.isEmpty()) 0.0 else values.sum() / values.size
        val meaning = positives.map { recall(it.byMeaning.take(K), it.question.expected) }
        val words = positives.map { recall(it.byWords.take(K), it.question.expected) }
        val either = positives.map { recall(it.byMeaning.take(K) + it.byWords.take(K), it.question.expected) }
        val negatives = outcomes.filter { it.question.isNegative }
        return Report(
            questions = outcomes.size,
            meaningRecall = mean(meaning),
            wordsRecall = mean(words),
            eitherRecall = mean(either),
            negatives = negatives.size,
            negativesClean = negatives.count { it.byMeaning.isEmpty() },
            missed = positives.zip(either).filter { it.second == 0.0 }.map { it.first.question.id }
        )
    }
}
