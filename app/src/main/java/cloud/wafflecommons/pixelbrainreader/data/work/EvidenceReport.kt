package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** A reporting period: a month or a quarter. */
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

/** The figures of a period, all counted from the log: nothing is estimated. */
data class EvidenceFigures(
    val entries: List<LogEntry>,
    val completed: List<WorkNote>
) {
    fun count(kind: LogKind) = entries.count { it.kind == kind }
    val openRequests: Int get() = entries.count(LeaveDocuments::isOpenRequest)
    val offTicket: List<LogEntry> get() = entries.filter { it.kind == LogKind.OFF_TICKET }

    /** Total minutes of the entries that recorded minutes; null when none did ("non mesuré"). */
    val offTicketMinutes: Int? get() = offTicket.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()
    val byRole: Map<String, List<LogEntry>> get() = entries.filter { it.role != null }.groupBy { it.role!! }
    val byTag: Map<String, Int> get() = entries.flatMap { it.tags }.groupingBy { it }.eachCount()
}

/**
 * The evidence report (#2): what a month or a quarter of the mission log shows, as a French
 * draft for an annual review or a status meeting. Counts come from the log; work completed comes
 * from the notes moved to done in the work folders during the period.
 */
object EvidenceReport {

    fun path(period: ReportPeriod): String = "${VaultSchema.INBOX_FOLDER}/Rapport d'activité ${period.key}.md"

    fun render(period: ReportPeriod, figures: EvidenceFigures, generated: LocalDate, summary: String? = null): String = buildString {
        append(VaultSchema.frontmatter(title = "Rapport d'activité — ${period.frenchLabel}", path = path(period), date = generated, tags = listOf("rapport-activite")))
        append("# Rapport d'activité — ").append(period.frenchLabel).append("\n\n")
        append("> Brouillon généré le ").append(generated).append(" à partir du journal de mission (")
            .append(plural(figures.entries.size, "entrée")).append(", du ").append(period.start).append(" au ").append(period.end)
            .append("). Tous les chiffres viennent des données ; rien n'est estimé.\n\n")
        summary?.let {
            append("## Synthèse\n\n> Rédigée sur l'appareil (Gemini Nano), à vérifier.\n\n").append(it.trim()).append("\n\n")
        }
        append("## En chiffres\n\n| | Nombre |\n|---|---|\n")
        append("| Faits | ").append(figures.count(LogKind.FACT)).append(" |\n")
        append("| Décisions | ").append(figures.count(LogKind.DECISION)).append(" |\n")
        append("| Demandes | ").append(figures.count(LogKind.REQUEST)).append(" (dont ").append(plural(figures.openRequests, "ouverte")).append(") |\n")
        append("| Refus | ").append(figures.count(LogKind.REFUSAL)).append(" |\n")
        append("| Temps hors ticket | ").append(figures.offTicketMinutes?.let(::hours) ?: "non mesuré")
            .append(" (").append(plural(figures.offTicket.size, "entrée")).append(") |\n")
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
        append("## Travail terminé\n\n")
        if (figures.completed.isEmpty()) {
            append("_Aucune note des dossiers de travail passée à terminé sur la période._\n")
        } else {
            figures.completed.forEach { note ->
                append("- [[").append(note.linkName).append("]]")
                note.updated?.let { append(" — terminé le ").append(it) }
                append('\n')
            }
        }
    }

    fun facts(figures: EvidenceFigures): List<String> =
        figures.entries.filter { it.kind != LogKind.OFF_TICKET }.map { "${it.kind.word} : ${it.text}" } +
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
