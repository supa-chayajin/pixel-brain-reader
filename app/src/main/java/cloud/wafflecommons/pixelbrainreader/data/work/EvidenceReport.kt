package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** A reporting period: a month or a quarter (a week only inside the weekly review). */
sealed class ReportPeriod {
    abstract val start: LocalDate
    abstract val end: LocalDate
    abstract val key: String
    abstract val frenchLabel: String
    abstract fun previous(): ReportPeriod
    abstract fun next(): ReportPeriod

    data class Month(val month: YearMonth) : ReportPeriod() {
        override val start: LocalDate get() = month.atDay(1)
        override val end: LocalDate get() = month.atEndOfMonth()
        override val key: String get() = month.toString()
        override val frenchLabel: String get() = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.FRENCH) + " " + month.year
        override fun previous() = Month(month.minusMonths(1))
        override fun next() = Month(month.plusMonths(1))
    }

    /** An ISO week (Monday to Sunday), for the weekly review's work counts (D6); never a report of its own. */
    data class Week(val monday: LocalDate) : ReportPeriod() {
        override val start: LocalDate get() = monday
        override val end: LocalDate get() = monday.plusDays(6)
        override val key: String
            get() = "${monday.get(IsoFields.WEEK_BASED_YEAR)}-W" + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')
        override val frenchLabel: String get() = "semaine ${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} ${monday.get(IsoFields.WEEK_BASED_YEAR)}"
        override fun previous() = Week(monday.minusDays(7))
        override fun next() = Week(monday.plusDays(7))
    }

    data class Quarter(val year: Int, val quarter: Int) : ReportPeriod() {
        override val start: LocalDate get() = LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
        override val end: LocalDate get() = start.plusMonths(3).minusDays(1)
        override val key: String get() = "$year-T$quarter"
        override val frenchLabel: String get() = "T$quarter $year"
        override fun previous() = if (quarter == 1) Quarter(year - 1, 4) else Quarter(year, quarter - 1)
        override fun next() = if (quarter == 4) Quarter(year + 1, 1) else Quarter(year, quarter + 1)

        companion object {
            fun of(date: LocalDate) = Quarter(date.year, (date.monthValue - 1) / 3 + 1)
        }
    }
}

/**
 * Whose activity a report covers: [WORK] is the mission log and what sits in the work folders;
 * [PERSONAL] is everything outside them (no mission log). Without work folders, there is no
 * split: both include everything.
 */
enum class EvidenceScope {
    WORK, PERSONAL;

    fun includes(path: String, workFolders: List<String>): Boolean {
        if (workFolders.isEmpty()) return true
        val work = workFolders.any { path == it || path.startsWith("$it/") }
        return if (this == WORK) work else !work
    }
}

/** One project's activity in a period, from its worklogs and notes: nothing is estimated. */
data class ProjectPeriodActivity(
    val root: String,
    val title: String,
    val commits: Int,
    val sessions: Int,
    /** Its key notes (README, roadmap, RFC…) moved to done during the period. */
    val doneNotes: List<WorkNote>
) {
    val name: String get() = root.substringAfterLast('/')
}

/** An objective that moved in the period: log material dated in it, or its note updated in it. */
data class ObjectivePeriodProgress(val title: String, val linkName: String, val done: Int, val total: Int, val material: Int)

/** The figures of a period, all counted from the vault and the log: nothing is estimated. */
data class EvidenceFigures(
    val entries: List<LogEntry>,
    val completed: List<WorkNote>,
    val meetings: List<Meeting> = emptyList(),
    val projectActivity: List<ProjectPeriodActivity> = emptyList(),
    val objectivesProgress: List<ObjectivePeriodProgress> = emptyList(),
    val scope: EvidenceScope = EvidenceScope.WORK
) {
    fun count(kind: LogKind) = entries.count { it.kind == kind }
    val openRequests: Int get() = entries.count(LeaveDocuments::isOpenRequest)
    val offTicket: List<LogEntry> get() = entries.filter { it.kind == LogKind.OFF_TICKET }

    /** Total minutes of the entries that recorded minutes; null when none did ("non mesuré"). */
    val offTicketMinutes: Int? get() = offTicket.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()
    val byRole: Map<String, List<LogEntry>> get() = entries.filter { it.role != null }.groupBy { it.role!! }

    /** Entries by requester (`[demandeur:: …]`, a role, never a name). */
    val byRequester: Map<String, List<LogEntry>> get() = entries.filter { it.requester != null }.groupBy { it.requester!! }
    val byTag: Map<String, Int> get() = entries.flatMap { it.tags }.groupingBy { it }.eachCount()

    /** Minutes of the meetings that recorded a duration; null when none did ("non mesuré"). */
    val meetingMinutes: Int? get() = meetings.mapNotNull { it.durationMinutes }.takeIf { it.isNotEmpty() }?.sum()
    val projectCommits: Int get() = projectActivity.sumOf { it.commits }
}

/**
 * The evidence report (#2): what a month or a quarter shows, as a French draft for an annual
 * review or a status meeting. The work report counts the mission log, the meetings, the work
 * projects' worklogs and the objectives; work completed comes from the notes moved to done in
 * the work folders. The personal report does the same outside the work folders, without the log.
 */
object EvidenceReport {

    fun path(period: ReportPeriod, scope: EvidenceScope = EvidenceScope.WORK): String =
        "${VaultSchema.INBOX_FOLDER}/${name(scope)} ${period.key}.md"

    private fun name(scope: EvidenceScope) = if (scope == EvidenceScope.PERSONAL) "Rapport d'activité personnel" else "Rapport d'activité"

    /** The report as a note; [at] is where it will live (the inbox unless the monthly job places it). */
    fun render(
        period: ReportPeriod,
        figures: EvidenceFigures,
        generated: LocalDate,
        summary: String? = null,
        at: String = path(period, figures.scope)
    ): String = buildString {
        val work = figures.scope == EvidenceScope.WORK
        val title = "${name(figures.scope)} — ${period.frenchLabel}"
        append(VaultSchema.frontmatter(title = title, path = at, date = generated, tags = listOf("rapport-activite")))
        append("# ").append(title).append("\n\n")
        append("> Brouillon généré le ").append(generated)
        if (work) {
            append(" à partir du journal de mission (").append(plural(figures.entries.size, "entrée")).append(", ")
        } else {
            append(" à partir des projets et des notes hors des dossiers de travail (")
        }
        append("du ").append(period.start).append(" au ").append(period.end)
            .append("). Tous les chiffres viennent des données ; rien n'est estimé.\n\n")
        summary?.let {
            append("## Synthèse\n\n> Rédigée sur l'appareil (Gemini Nano), à vérifier.\n\n").append(it.trim()).append("\n\n")
        }
        append("## En chiffres\n\n| | Nombre |\n|---|---|\n")
        if (work) {
            append("| Faits | ").append(figures.count(LogKind.FACT)).append(" |\n")
            append("| Décisions | ").append(figures.count(LogKind.DECISION)).append(" |\n")
            append("| Demandes | ").append(figures.count(LogKind.REQUEST)).append(" (dont ").append(plural(figures.openRequests, "ouverte")).append(") |\n")
            append("| Refus | ").append(figures.count(LogKind.REFUSAL)).append(" |\n")
            append("| Temps hors ticket | ").append(figures.offTicketMinutes?.let(::hours) ?: "non mesuré")
                .append(" (").append(plural(figures.offTicket.size, "entrée")).append(") |\n")
        }
        if (figures.meetings.isNotEmpty()) {
            append("| Réunions | ").append(figures.meetings.size).append(" (").append(figures.meetingMinutes?.let(::hours) ?: "durée non mesurée").append(") |\n")
        }
        if (figures.projectCommits > 0) {
            append("| Commits (projets) | ").append(figures.projectCommits).append(" sur ")
                .append(plural(figures.projectActivity.count { it.commits > 0 }, "projet")).append(" |\n")
        }
        append("| Travail terminé | ").append(plural(figures.completed.size, "note")).append(" |\n\n")
        if (figures.byRole.isNotEmpty()) {
            append("## Par rôle\n\n")
            figures.byRole.entries.sortedByDescending { it.value.size }.forEach { (role, entries) ->
                append("- ").append(role).append(" : ").append(plural(entries.size, "entrée"))
                entries.filter { it.kind == LogKind.OFF_TICKET }.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.let {
                    append(" · ").append(hours(it.sum())).append(" hors ticket")
                }
                append('\n')
            }
            append('\n')
        }
        if (figures.byRequester.isNotEmpty()) {
            append("## Par demandeur\n\n")
            figures.byRequester.entries.sortedWith(compareByDescending<Map.Entry<String, List<LogEntry>>> { it.value.size }.thenBy { it.key })
                .forEach { (requester, entries) ->
                    val requests = entries.filter { it.kind == LogKind.REQUEST }
                    append("- ").append(requester).append(" : ").append(plural(requests.size, "demande"))
                    val open = requests.count(LeaveDocuments::isOpenRequest)
                    if (open > 0) append(" (dont ").append(plural(open, "ouverte")).append(')')
                    entries.filter { it.kind == LogKind.OFF_TICKET }.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.let {
                        append(" · ").append(hours(it.sum())).append(" hors ticket")
                    }
                    append('\n')
                }
            append('\n')
        }
        if (figures.byTag.isNotEmpty()) {
            append("## Par thème\n\n")
            figures.byTag.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .forEach { (tag, count) -> append("- #").append(tag).append(" : ").append(count).append('\n') }
            append('\n')
        }
        listing("Décisions", figures.entries.filter { it.kind == LogKind.DECISION })
        listing("Demandes", figures.entries.filter { it.kind == LogKind.REQUEST })
        listing("Refus", figures.entries.filter { it.kind == LogKind.REFUSAL })
        listing("Faits", figures.entries.filter { it.kind == LogKind.FACT })
        if (figures.objectivesProgress.isNotEmpty()) {
            append("## Objectifs\n\n")
            figures.objectivesProgress.forEach { o ->
                append("- [[").append(o.linkName).append("]] : ")
                append(if (o.total == 0) "aucun jalon" else "${o.done} jalon${if (o.done > 1) "s" else ""} sur ${o.total}")
                if (o.material > 0) append(" · ").append(plural(o.material, "entrée")).append(" du journal sur la période")
                append('\n')
            }
            append('\n')
        }
        if (figures.projectActivity.isNotEmpty()) {
            append("## Projets actifs\n\n")
            figures.projectActivity.forEach { p ->
                append("- [[").append(p.root).append("/README|").append(p.title).append("]] : ").append(plural(p.commits, "commit"))
                if (p.sessions > 0) append(" · ").append(plural(p.sessions, "session")).append(" IA")
                append('\n')
                p.doneNotes.forEach { note ->
                    append("  - [[").append(note.linkName).append("]]")
                    note.updated?.let { append(" — terminé le ").append(it) }
                    append('\n')
                }
            }
            append('\n')
        }
        append("## Travail terminé\n\n")
        if (figures.completed.isEmpty()) {
            append(
                if (work) "_Aucune note des dossiers de travail passée à terminé sur la période._\n"
                else "_Aucune note de projet personnel passée à terminé sur la période._\n"
            )
        } else {
            figures.completed.forEach { note ->
                append("- [[").append(note.linkName).append("]]")
                note.updated?.let { append(" — terminé le ").append(it) }
                append('\n')
            }
        }
    }

    /**
     * The facts handed to Nano for the summary, most telling first (the prompt keeps what fits its
     * budget): log entries prefixed with the role held, then objectives, projects and work done.
     */
    fun facts(figures: EvidenceFigures): List<String> =
        figures.entries.filter { it.kind != LogKind.OFF_TICKET }.map { e -> (e.role?.let { "[$it] " } ?: "") + "${e.kind.word} : ${e.text}" } +
            figures.objectivesProgress.map { o -> "Objectif « ${o.title} » : ${o.done} jalon${if (o.done > 1) "s" else ""} sur ${o.total}" } +
            figures.projectActivity.filter { it.commits > 0 || it.sessions > 0 }.map { p ->
                "Projet ${p.title} : ${plural(p.commits, "commit")}" + if (p.sessions > 0) ", ${plural(p.sessions, "session")} IA" else ""
            } +
            figures.completed.map { "Terminé : ${it.title}" }

    private fun StringBuilder.listing(title: String, entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        append("## ").append(title).append("\n\n")
        entries.forEach { entry ->
            append("- ").append(entry.at.toLocalDate()).append(" — ").append(entry.text)
            val meta = listOfNotNull(
                entry.role,
                entry.requester?.let { "demandé par : $it" },
                entry.decider?.let { "décidé par : $it" },
                entry.status?.let { "statut : $it" },
                entry.reference
            )
            if (meta.isNotEmpty()) append(" (").append(meta.joinToString(" · ")).append(')')
            append('\n')
        }
        append('\n')
    }

    /** French plural: 0 and 1 take the singular. */
    fun plural(count: Int, word: String): String = "$count $word" + if (count > 1) "s" else ""

    /** "6 h 40". */
    fun hours(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}
