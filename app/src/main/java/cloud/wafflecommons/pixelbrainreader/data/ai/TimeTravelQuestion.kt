package cloud.wafflecommons.pixelbrainreader.data.ai

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * A question about the past (K4, 12.20): "qu'est-ce que je pensais de X il y a un an ?". The day it
 * looks back to and the question without the words that said when, which retrieval then reads.
 */
data class ThenQuestion(val then: LocalDate, val topic: String)

/**
 * Finds the time in a question, French or English: "il y a un an / 6 mois / deux semaines", "l'an
 * dernier", "en mars 2025", "en 2024", "a year ago", "last year", "in March 2025", "in 2024". A
 * month or a year means its last day, the note as it stood by then. The question is lowercased
 * before matching and boundaries are explicit, so the JVM's tests and Android's ICU engine agree.
 * Pure.
 */
object TimeTravelQuestion {

    private val NUMBERS = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12
    )

    private val MONTHS = mapOf(
        "janvier" to 1, "février" to 2, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6,
        "juillet" to 7, "août" to 8, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11,
        "décembre" to 12, "decembre" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7,
        "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12
    )

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val COUNT = "(\\d{1,3}|" + NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private val MONTH = "(" + MONTHS.keys.sortedByDescending { it.length }.joinToString("|") + ")"

    private val AGO_FR = Regex("${B}il y a $COUNT (ans?|années?|mois|semaines?|jours?)$E")
    private val AGO_EN = Regex("$B$COUNT (years?|months?|weeks?|days?) ago$E")
    private val LAST_YEAR = Regex("${B}(l'an dernier|l’an dernier|l'année dernière|l’année dernière|l'an passé|l’an passé|l'année passée|l’année passée|last year)$E")
    private val MONTH_YEAR = Regex("$B(?:en|in) $MONTH (\\d{4})$E")
    private val YEAR = Regex("$B(?:en|in) (\\d{4})$E")

    fun parse(question: String, today: LocalDate): ThenQuestion? {
        val text = question.lowercase(Locale.ROOT)
        val found = AGO_FR.find(text)?.let { it to ago(it.groupValues[1], it.groupValues[2], today) }
            ?: AGO_EN.find(text)?.let { it to ago(it.groupValues[1], it.groupValues[2], today) }
            ?: LAST_YEAR.find(text)?.let { it to today.minusYears(1) }
            ?: MONTH_YEAR.find(text)?.let { match ->
                val month = MONTHS.getValue(match.groupValues[1])
                match to YearMonth.of(match.groupValues[2].toInt(), month).atEndOfMonth()
            }
            ?: YEAR.find(text)?.let { it to LocalDate.of(it.groupValues[1].toInt(), 12, 31) }
            ?: return null
        val (match, then) = found
        if (then == null || !then.isBefore(today)) return null
        val topic = (question.substring(0, match.range.first) + question.substring(match.range.last + 1))
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return ThenQuestion(then, topic.ifBlank { question.trim() })
    }

    private fun ago(count: String, unit: String, today: LocalDate): LocalDate? {
        val n = count.toIntOrNull() ?: NUMBERS[count] ?: return null
        if (n <= 0) return null
        return when {
            unit.startsWith("an") || unit.startsWith("ann") || unit.startsWith("year") -> today.minusYears(n.toLong())
            unit.startsWith("mois") || unit.startsWith("month") -> today.minusMonths(n.toLong())
            unit.startsWith("semaine") || unit.startsWith("week") -> today.minusWeeks(n.toLong())
            unit.startsWith("jour") || unit.startsWith("day") -> today.minusDays(n.toLong())
            else -> null
        }
    }
}
