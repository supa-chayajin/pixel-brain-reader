package cloud.wafflecommons.pixelbrainreader.data.capture

import java.time.LocalTime

/** Where one line typed in the Daily "Add…" box goes. */
enum class CaptureKind(val label: String) {
    TASK("Task"),
    MOMENT("Moment"),
    GRATITUDE("Gratitude"),
    IDEA("Idea"),
    SCRAP("Scrap");

    /** The next destination when the user taps the chip to correct the guess. */
    fun next(): CaptureKind = entries[(ordinal + 1) % entries.size]
}

/** The router's guess: the destination, the text to store, and a time read from the line. */
data class CaptureGuess(
    val kind: CaptureKind,
    val text: String,
    val time: LocalTime? = null
)

/**
 * Files one typed line into the right part of the day, so the Daily screen needs one input
 * instead of six. Pure and deterministic (tested on the JVM); no regex word boundaries, so ICU
 * and the JVM agree.
 *
 * Rules, first match wins:
 * 1. An explicit prefix — `t:` task, `m:` moment, `g:` gratitude, `i:` idea, `s:` scrap — or a
 *    markdown checkbox `[ ]` / `- [ ]` (task).
 * 2. Gratitude words (`merci`, `reconnaissant`, `gratitude`, `thanks`, `grateful`…).
 * 3. Idea openers (`idée`, `idea`, `et si`, `what if`).
 * 4. A to-do verb at the start (`acheter`, `appeler`, `buy`, `call`, `penser à`…) → task; a time
 *    in it (`à 14h`, `14:30`) schedules it.
 * 5. A line that starts with a time (`14h appel banque`) → a moment at that time.
 * 6. Anything else → a scrap (the scratchpad), the safest place to land.
 */
object CaptureRouter {

    private val prefixes = mapOf(
        "t:" to CaptureKind.TASK,
        "m:" to CaptureKind.MOMENT,
        "g:" to CaptureKind.GRATITUDE,
        "i:" to CaptureKind.IDEA,
        "s:" to CaptureKind.SCRAP
    )

    private val gratitudeStarts = listOf(
        "merci", "reconnaissant", "reconnaissante", "gratitude", "grateful",
        "thanks", "thank you", "heureux de", "heureuse de", "content de", "contente de"
    )

    private val ideaStarts = listOf("idée", "idee", "idea", "et si ", "what if ", "💡")

    private val taskStarts = listOf(
        // French
        "acheter", "appeler", "rappeler", "envoyer", "payer", "réserver", "reserver", "prendre rdv",
        "prendre rendez-vous", "penser à", "penser a", "ne pas oublier", "faire ", "finir", "terminer",
        "préparer", "preparer", "écrire", "ecrire", "répondre", "repondre", "récupérer", "recuperer",
        "ranger", "réviser", "reviser", "nettoyer", "laver", "réparer", "reparer", "commander", "imprimer", "vérifier",
        "verifier", "relancer", "contacter", "planifier", "à faire", "a faire", "todo", "to do",
        // English
        "buy ", "call ", "send ", "pay ", "book ", "remember to", "don't forget", "fix ", "finish ",
        "write ", "reply ", "email ", "order ", "clean ", "check ", "pick up", "schedule "
    )

    fun guess(raw: String): CaptureGuess {
        val line = raw.trim().replace('\n', ' ')
        if (line.isEmpty()) return CaptureGuess(CaptureKind.SCRAP, "")
        val lower = line.lowercase()

        // 1. Explicit prefixes and checkboxes.
        for ((prefix, kind) in prefixes) {
            if (lower.startsWith(prefix)) {
                val rest = line.substring(prefix.length).trim()
                return withTime(kind, rest)
            }
        }
        for (box in listOf("- [ ]", "[ ]", "-[ ]")) {
            if (lower.startsWith(box)) return withTime(CaptureKind.TASK, line.substring(box.length).trim())
        }

        // 2–4. Keyword openers.
        if (gratitudeStarts.any { startsWithWord(lower, it) }) return CaptureGuess(CaptureKind.GRATITUDE, line)
        if (ideaStarts.any { startsWithWord(lower, it) }) return CaptureGuess(CaptureKind.IDEA, stripIdeaLabel(line))
        if (taskStarts.any { startsWithWord(lower, it) }) return taskGuess(line)

        // 5. A leading time is a moment.
        leadingTime(line)?.let { (time, rest) ->
            if (rest.isNotBlank()) return CaptureGuess(CaptureKind.MOMENT, rest, time)
        }

        // 6. Fallback.
        return CaptureGuess(CaptureKind.SCRAP, line)
    }

    /** Re-targets a guess the user corrected, keeping the text and any time it carried. */
    fun retarget(guess: CaptureGuess, kind: CaptureKind): CaptureGuess = guess.copy(kind = kind)

    private fun withTime(kind: CaptureKind, text: String): CaptureGuess = when (kind) {
        CaptureKind.MOMENT -> leadingTime(text)?.let { (t, rest) -> CaptureGuess(kind, rest, t) }
            ?: CaptureGuess(kind, text)
        CaptureKind.TASK -> taskGuess(text)
        else -> CaptureGuess(kind, text)
    }

    /** A task with its time read out of the label: "appeler la banque à 14h" → "appeler la banque" at 14:00. */
    private fun taskGuess(text: String): CaptureGuess {
        val found = findTimeRange(text) ?: return CaptureGuess(CaptureKind.TASK, text)
        var before = text.substring(0, found.second.first).trimEnd()
        val lowerBefore = before.lowercase()
        for (prep in listOf(" à", " a", " at", " vers", " @")) {
            if (lowerBefore.endsWith(prep)) { before = before.dropLast(prep.length).trimEnd(); break }
        }
        val after = text.substring(found.second.last + 1).trim()
        val label = listOf(before, after).filter { it.isNotEmpty() }.joinToString(" ")
        return CaptureGuess(CaptureKind.TASK, label.ifBlank { text }, found.first)
    }

    /** `idée : xyz` → `xyz`; `et si…` stays as written (it IS the idea). */
    private fun stripIdeaLabel(line: String): String {
        val lower = line.lowercase()
        for (label in listOf("idée", "idee", "idea")) {
            if (lower.startsWith(label)) {
                val rest = line.substring(label.length).trimStart(' ', ':', '-', '—', '–')
                return rest.ifBlank { line }
            }
        }
        return line
    }

    /** True when [text] starts with [word] followed by the end, a space or punctuation. */
    internal fun startsWithWord(text: String, word: String): Boolean {
        if (!text.startsWith(word)) return false
        if (text.length == word.length) return true
        if (word.endsWith(' ')) return true
        val next = text[word.length]
        return !next.isLetterOrDigit()
    }

    /** A time at the start of [line] (`14h`, `14h30`, `14:30`, `9h05`) and the text after it. A duration ("2h de sport") is not a time. */
    internal fun leadingTime(line: String): Pair<LocalTime, String>? {
        val parsed = parseTimeAt(line, 0) ?: return null
        if (isDuration(line, parsed.end)) return null
        val rest = line.substring(parsed.end).trimStart(' ', ':', '-', '—', '–', ',')
        return parsed.time to rest
    }

    /** The first time written anywhere in [text], as a word of its own. */
    internal fun findTime(text: String): LocalTime? = findTimeRange(text)?.first

    /**
     * The first time in [text] and the character range it covers. Mid-line, a bare hour needs a
     * preposition before it ("à 18h", "at 9h"); one with minutes ("16:30", "14h30") stands alone.
     * "réviser 1h" or "2h de sport" are durations, not times.
     */
    private fun findTimeRange(text: String): Pair<LocalTime, IntRange>? {
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                val parsed = parseTimeAt(text, i)
                if (parsed != null && !isDuration(text, parsed.end) &&
                    (parsed.hasMinutes || precededByPreposition(text, i))
                ) {
                    return parsed.time to (i until parsed.end)
                }
            }
            i++
        }
        return null
    }

    private val timePrepositions = listOf("à", "a", "at", "vers", "@", "dès", "avant", "après", "before", "after", "by")
    private val durationWords = listOf("de ", "d'", "d’", "of ", "min", "mn", "heure", "hour", "hr")

    private fun precededByPreposition(text: String, timeStart: Int): Boolean {
        val before = text.substring(0, timeStart).trimEnd().lowercase()
        return timePrepositions.any { p -> before == p || before.endsWith(" $p") }
    }

    /** The words right after a time say it is a length of time: "2h de sport", "1h30 of work". */
    private fun isDuration(text: String, end: Int): Boolean {
        val after = text.substring(end).trimStart().lowercase()
        return durationWords.any { after.startsWith(it) }
    }

    private data class ParsedTime(val time: LocalTime, val end: Int, val hasMinutes: Boolean)

    /** Parses `H[H](h|:)[MM]` at [start]. */
    private fun parseTimeAt(s: String, start: Int): ParsedTime? {
        var i = start
        val hourStart = i
        while (i < s.length && s[i].isDigit() && i - hourStart < 2) i++
        if (i == hourStart || i >= s.length) return null
        val hour = s.substring(hourStart, i).toInt()
        val sep = s[i]
        if (sep != 'h' && sep != 'H' && sep != ':') return null
        i++
        val minStart = i
        while (i < s.length && s[i].isDigit() && i - minStart < 2) i++
        val minuteText = s.substring(minStart, i)
        if (sep == ':' && minuteText.length != 2) return null
        if (minuteText.length == 1) return null
        val minute = if (minuteText.isEmpty()) 0 else minuteText.toInt()
        // The time must end the word: "14h30" yes, "14hrs" / "3h2x" no.
        if (i < s.length && s[i].isLetterOrDigit()) return null
        if (hour > 23 || minute > 59) return null
        return ParsedTime(LocalTime.of(hour, minute), i, minuteText.isNotEmpty())
    }
}
