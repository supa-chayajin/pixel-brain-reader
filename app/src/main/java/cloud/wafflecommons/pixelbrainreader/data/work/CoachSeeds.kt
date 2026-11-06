package cloud.wafflecommons.pixelbrainreader.data.work

import java.time.LocalDate

/**
 * The first message a work screen sends to the 🧭 Coach preset ("Ask Coach"): a short French
 * brief built from what the screen shows, so the coach rephrases and asks one open question
 * without the user re-explaining the week. Only measured facts: a duration nobody recorded is
 * left out, never shown as zero. Each brief stays under [MAX_CHARS] (Nano's window is small, and
 * the persona and the history share it); the question at the end always fits.
 */
object CoachSeeds {

    const val COACH_PRESET = "coach"
    const val MAX_CHARS = 1_200
    private const val MAX_FACT = 180

    /** From an objective's card: its milestones, its linked projects and its latest log material. */
    fun objective(objective: Objective, today: LocalDate): String {
        val note = objective.note
        val facts = buildList {
            objective.milestones.filter { !it.done }
                .sortedWith(compareBy<Milestone> { it.due == null }.thenBy { it.due })
                .forEach { m ->
                    add("Jalon ouvert : ${m.text}" + (m.due?.let { due -> if (due < today) " (en retard, échéance $due)" else " (échéance $due)" } ?: ""))
                }
            objective.linkedProjects.forEach { p -> add("Projet lié : ${p.title}, ${EvidenceReport.plural(p.commits, "commit")} en 14 jours") }
            objective.material.sortedByDescending { it.at }.take(5).forEach { e -> add("Journal ${e.at.toLocalDate()} (${e.kind.word}) : ${e.text}") }
            if (objective.stale(today)) add("Rien n'a bougé depuis trois semaines.")
        }
        val progress = if (objective.milestones.isEmpty()) "aucun jalon" else "${objective.done} jalon${if (objective.done > 1) "s" else ""} sur ${objective.milestones.size}"
        return compose(
            "Mon objectif « ${note.title} » : $progress.",
            facts,
            "Aide-moi à choisir le prochain pas concret."
        )
    }

    /** From load vs recovery: the week against the usual one, what drains it, what's still open. */
    fun load(report: LoadRecoveryReport): String {
        val b = report.breakdown
        val facts = buildList {
            report.overload?.let { o ->
                add("Réunions et hors-ticket : ${EvidenceReport.hours(o.weekMinutes)} sur 7 jours, contre ${EvidenceReport.hours(o.usualWeekMinutes)} d'habitude (+${o.loadIncreasePercent} %).")
                add("Ma récupération est sous ma norme cette semaine.")
            }
            b.byRole.take(3).forEach { add("Rôle ${it.name} : ${share(it)}") }
            b.byRequester.take(3).forEach { add("Demandeur ${it.name} : ${share(it)}") }
            b.openRequests.take(5).forEach { add("Demande ouverte : ${it.text}") }
            if (b.overdueMyActions > 0) add("${EvidenceReport.plural(b.overdueMyActions, "action")} à moi en retard.")
        }
        return compose(
            "Ma charge des 7 derniers jours, d'après mon journal de mission.",
            facts.ifEmpty { listOf("Rien de noté cette semaine par rôle ni par demandeur.") },
            "Qu'est-ce que je devrais refuser, renégocier ou transformer en ticket ?"
        )
    }

    /** From the evidence report: the period's figures by role, its decisions and its finished work. */
    fun evidence(periodLabel: String, figures: EvidenceFigures): String {
        val facts = buildList {
            figures.byRole.entries.sortedByDescending { it.value.size }.forEach { (role, entries) -> add("Rôle $role : ${EvidenceReport.plural(entries.size, "entrée")}") }
            figures.entries.filter { it.kind == LogKind.DECISION }.take(5).forEach { add("Décision : ${it.text}") }
            figures.objectivesProgress.forEach { o -> add("Objectif « ${o.title} » : ${o.done} jalon${if (o.done > 1) "s" else ""} sur ${o.total}") }
            figures.projectActivity.filter { it.commits > 0 }.take(5).forEach { p -> add("Projet ${p.title} : ${EvidenceReport.plural(p.commits, "commit")}") }
            figures.completed.take(5).forEach { add("Terminé : ${it.title}") }
        }
        val scope = if (figures.scope == EvidenceScope.PERSONAL) "personnel" else "pro"
        val intro = if (figures.scope == EvidenceScope.WORK) {
            "Mon bilan $scope de $periodLabel : ${EvidenceReport.plural(figures.entries.size, "entrée")} dans mon journal de mission."
        } else {
            "Mon bilan $scope de $periodLabel."
        }
        return compose(intro, facts, "Quel résultat manque au journal avant que j'écrive le bilan ?")
    }

    /** "1 h 30 hors ticket, 2 demandes dont 1 ouverte, 1 refus": recorded minutes only. */
    private fun share(s: LoadShare): String = buildList {
        s.offTicketMinutes?.let { add("${EvidenceReport.hours(it)} hors ticket") }
        if (s.requests > 0) add(EvidenceReport.plural(s.requests, "demande") + if (s.openRequests > 0) " dont ${EvidenceReport.plural(s.openRequests, "ouverte")}" else "")
        if (s.refusals > 0) add("${s.refusals} refus")
    }.joinToString(", ").ifEmpty { "noté, sans durée ni demande" }

    /** The intro, as many whole facts as fit, then the question: never over [MAX_CHARS]. */
    internal fun compose(intro: String, facts: List<String>, question: String): String {
        val budget = MAX_CHARS - question.length - 2
        val out = StringBuilder(intro.take(budget))
        for (fact in facts) {
            val line = "\n- " + fact.replace('\n', ' ').trim().let { if (it.length > MAX_FACT) it.take(MAX_FACT - 1) + "…" else it }
            if (out.length + line.length > budget) break
            out.append(line)
        }
        return out.append("\n\n").append(question).toString()
    }
}
