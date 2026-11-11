package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.work.CoachSeeds
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind

/**
 * "Ask Coach about this week" (D6): the week's measured facts as a French brief for the Coach
 * preset, cut to [CoachSeeds.MAX_CHARS] by [CoachSeeds.compose]. Nano can't write into the note from
 * the nightly job (AICore refuses a background caller), so the reflection is a conversation the
 * owner starts (owner, 2026-11-11). Work stays counts only, like the note.
 */
object WeeklyCoach {

    const val QUESTION = "Aide-moi à en tirer trois enseignements et à choisir un cap pour la semaine prochaine."

    fun brief(week: ReviewWeek, facts: WeeklyFacts): String {
        val f = WeeklyReviewFormat
        val lines = buildList {
            facts.mood?.let { mood ->
                add(
                    "Humeur moyenne : ${f.decimal(mood.average)}/5 sur ${f.count(mood.days, "jour", "jours")}" +
                        (mood.previousAverage?.let { ", contre ${f.decimal(it)} la semaine précédente" } ?: "") + "."
                )
            }
            facts.health?.let { health ->
                health.sleepMinutesAverage?.let { add("Sommeil moyen : ${f.duration(it)} sur ${f.count(health.nights, "nuit", "nuits")}.") }
                health.stepsAverage?.let { add("Pas : ${f.number(it)} par jour en moyenne.") }
            }
            if (facts.tasksTotal > 0) add("Tâches faites : ${facts.tasksDone} sur ${facts.tasksTotal}.")
            facts.habits.forEach { habit ->
                add(
                    "Habitude « ${habit.title} » : " +
                        if (habit.planned != null && habit.planned > 0) "${habit.done} jours sur ${habit.planned} prévus." else "${habit.done} fois."
                )
            }
            if (facts.choresDone.isNotEmpty()) add("Corvées faites : ${facts.choresDone.size}.")
            facts.xpGained?.takeIf { it > 0 }?.let { add("XP gagnée : $it.") }
            facts.notes?.takeIf { !it.isEmpty }?.let { notes ->
                add(
                    "Notes : ${f.count(notes.created.size + notes.workCreated, "créée", "créées")}, " +
                        "${f.count(notes.edited.size + notes.workEdited, "modifiée", "modifiées")}."
                )
            }
            facts.work?.takeIf { !it.isEmpty }?.let { work ->
                val entries = work.entries.values.sum()
                if (entries > 0) {
                    val decisions = work.entries[LogKind.DECISION] ?: 0
                    add(
                        "Journal de mission : ${f.count(entries, "entrée", "entrées")}, dont " +
                            f.count(decisions, "décision", "décisions") +
                            (if (work.decisionsWithoutRecord > 0) " (${work.decisionsWithoutRecord} sans ADR)" else "") + "."
                    )
                }
                if (work.meetings > 0) add("Réunions : ${work.meetings}.")
                if (work.objectivesMoved + work.objectivesStalled > 0) {
                    add("Objectifs : ${work.objectivesMoved} en progrès, ${work.objectivesStalled} au point mort.")
                }
                work.overload?.let { add("Charge : +${it.loadIncreasePercent} % par rapport à d'habitude.") }
            }
            facts.lastWeekPlan.forEach { line -> add("Prévu la semaine dernière : " + planText(line)) }
        }
        return CoachSeeds.compose(
            "Ma " + f.title(week).replaceFirstChar { it.lowercase() } + ", d'après mes données.",
            lines.ifEmpty { listOf("Rien n'a été mesuré cette semaine.") },
            QUESTION
        )
    }

    /** A plan line without its list marker or checkbox. */
    private fun planText(line: String): String {
        var text = line.trim()
        for (marker in listOf("- ", "* ", "+ ")) text = text.removePrefix(marker)
        for (box in listOf("[ ] ", "[x] ", "[X] ")) text = text.removePrefix(box)
        return text.trim()
    }
}
