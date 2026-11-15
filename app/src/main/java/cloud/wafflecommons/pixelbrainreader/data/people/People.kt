package cloud.wafflecommons.pixelbrainreader.data.people

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.work.CoachSeeds
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which people a mode shows (12.19, owner's D1). */
object People {

    /** Work people only while work isn't hidden; personal people, and notes that don't open (they say nothing), always. */
    fun visible(people: List<Person>, workHidden: Boolean): List<Person> =
        if (!workHidden) people else people.filter { it.note == null || it.note.kind == PersonKind.PERSONAL }
}

/**
 * Ask Coach to prepare a 1:1 (12.19, owner's D9): a French brief from a person's note — work or
 * personal, their role, the open points, both sides' open actions and when the last 1:1 was. Never
 * the name (also scrubbed from the points as typed), the context, the personal details or the
 * feedback. Within `CoachSeeds`' budget, ending with the app's French directive.
 */
object OneOnOneCoach {

    /** What stands in for the person's name where the owner typed it in a point. */
    const val NAME_STAND_IN = "X"

    fun prompt(note: PersonNote, today: LocalDate): String {
        val role = note.role.takeIf { it.isNotBlank() }?.let { scrub(it, note.name) }
        val who = when (note.kind) {
            PersonKind.WORK -> if (role != null) "une personne de mon travail (rôle : $role)" else "une personne de mon travail"
            PersonKind.PERSONAL -> if (role != null) "un proche ($role)" else "un proche"
        }
        val last = note.lastOneOnOne?.let { day ->
            when (val days = ChronoUnit.DAYS.between(day, today)) {
                in Long.MIN_VALUE..0L -> "Notre dernier tête-à-tête a eu lieu aujourd'hui."
                1L -> "Notre dernier tête-à-tête remonte à hier."
                else -> "Notre dernier tête-à-tête remonte à $days jours."
            }
        } ?: "C'est notre premier tête-à-tête noté."
        val facts = buildList {
            note.openPoints.filterNot { it.done }.forEach { add("Point ouvert : ${scrub(it.text, note.name)}") }
            note.myActions.filterNot { it.done }.forEach { add("Mon action en cours : ${scrub(it.text, note.name)}") }
            note.theirActions.filterNot { it.done }.forEach { add("Son action en cours : ${scrub(it.text, note.name)}") }
        }
        val intro = "Tu es un coach bienveillant. Aide-moi à préparer mon prochain tête-à-tête avec $who. $last"
        val question = "Propose un ordre du jour court (3 à 5 points, les plus importants d'abord) et une question " +
            "pour ouvrir l'échange. ${AiLanguage.DIRECTIVE}"
        return CoachSeeds.compose(intro, facts, question)
    }

    /**
     * [text] without the person's [name]: the whole name and each of its words of 3 letters or more,
     * as whole words, any case. The boundaries are written out (no `\b`): Android's regex engine is
     * ICU, and the tests run on the JVM.
     */
    fun scrub(text: String, name: String): String {
        val words = (listOf(name.trim()) + name.split(' ', '-', '\'').map { it.trim() })
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
        return words.fold(text) { acc, word ->
            Regex("""(?<![\p{L}\p{N}])${Regex.escape(word)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE).replace(acc, NAME_STAND_IN)
        }
    }
}
