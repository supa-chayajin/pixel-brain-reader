package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a handover draft is made of. Every figure comes from the vault; the summary is optional AI. */
data class HandoverInputs(
    val scope: String,
    val start: LocalDate,
    val end: LocalDate,
    val generated: LocalDate,
    val inProgress: List<WorkNote>,
    val paused: List<WorkNote>,
    val decisions: List<LogEntry>,
    val openRequests: List<LogEntry>,
    val refusals: List<LogEntry>,
    val keyDates: List<KeyDate>,
    val summary: String? = null
)

/** What the first day back is briefed with. */
data class BriefingInputs(
    val start: LocalDate,
    val end: LocalDate,
    val generated: LocalDate,
    val changed: List<WorkNote>,
    val created: List<WorkNote>,
    val logEntries: List<LogEntry>,
    val upcoming: List<KeyDate>,
    val summary: String? = null
)

/**
 * The leave documents (#3), in French like every generated document: a handover draft written
 * before leaving, and a "what happened" briefing for the first day back. Both are drafts in the
 * inbox, to review before sharing; who asked and who decided are roles.
 */
object LeaveDocuments {

    /** Requests whose status says they are closed. */
    private val CLOSED_WORDS = listOf("fait", "traité", "livré", "clos", "fermé", "done", "closed", "refusé", "annulé", "résolu")

    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

    /** Days after a leave during which the Daily screen offers the briefing. */
    const val RETURN_WINDOW_DAYS = 3L

    /** The leave that ended in the last [RETURN_WINDOW_DAYS] days, unless today is still on leave. */
    fun recentReturn(leaves: List<cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod>, today: LocalDate): cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod? {
        if (leaves.any { it.contains(today) }) return null
        return leaves
            .filter { leave ->
                val end = runCatching { LocalDate.parse(leave.end) }.getOrNull() ?: return@filter false
                end < today && !today.isAfter(end.plusDays(RETURN_WINDOW_DAYS))
            }
            .maxByOrNull { it.end }
    }

    fun isOpenRequest(entry: LogEntry): Boolean =
        entry.kind == LogKind.REQUEST && CLOSED_WORDS.none { entry.status?.lowercase()?.contains(it) == true }

    fun scopeName(scope: String): String = scope.trimEnd('/').substringAfterLast('/').ifEmpty { "Vault" }

    fun handoverPath(scope: String, start: LocalDate): String =
        "${VaultSchema.INBOX_FOLDER}/Passation ${scopeName(scope)} $start.md"

    fun briefingPath(start: LocalDate, end: LocalDate): String =
        "${VaultSchema.INBOX_FOLDER}/Retour d'absence $start au $end.md"

    fun handover(inputs: HandoverInputs): String = buildString {
        val name = scopeName(inputs.scope)
        val path = handoverPath(inputs.scope, inputs.start)
        append(VaultSchema.frontmatter(title = "Passation — $name — ${inputs.start} au ${inputs.end}", path = path, date = inputs.generated, tags = listOf("passation")))
        append("# Passation — ").append(name).append("\n\n")
        append("> Brouillon généré le ").append(DAY.format(inputs.generated))
            .append(" par Pixel Brain, à relire avant de le partager. Les rôles remplacent les noms.\n\n")
        append("**Périmètre** : `").append(inputs.scope).append("` · **Absence** : du ")
            .append(DAY.format(inputs.start)).append(" au ").append(DAY.format(inputs.end)).append("\n\n")
        inputs.summary?.let { summary ->
            append("## Synthèse\n\n> Rédigée sur l'appareil (Gemini Nano), à vérifier.\n\n").append(summary.trim()).append("\n\n")
        }
        section("En cours", inputs.inProgress.map(::noteLine), "Aucune note en cours dans le périmètre.")
        section("En pause ou bloqué", inputs.paused.map(::noteLine), "Rien en pause.")
        section("Décisions récentes", inputs.decisions.map(::logLine), "Aucune décision journalisée sur la période.")
        section("Demandes en attente", inputs.openRequests.map(::logLine), "Aucune demande ouverte au journal.")
        section("Refus", inputs.refusals.map(::logLine), "Aucun refus journalisé.")
        section("Échéances pendant l'absence", inputs.keyDates.map(::keyDateLine), "Aucune date clé pendant l'absence.")
        append("## Points d'attention\n\n")
        append("- [ ] Accès et droits à transmettre\n")
        append("- [ ] Rôles à prévenir de l'absence\n")
        append("- [ ] Risques et sujets sensibles\n")
    }

    fun briefing(inputs: BriefingInputs): String = buildString {
        val path = briefingPath(inputs.start, inputs.end)
        append(VaultSchema.frontmatter(title = "Retour d'absence — ${inputs.start} au ${inputs.end}", path = path, date = inputs.generated, tags = listOf("retour-absence")))
        append("# Retour d'absence\n\n")
        append("> Du ").append(DAY.format(inputs.start)).append(" au ").append(DAY.format(inputs.end))
            .append(". Généré le ").append(DAY.format(inputs.generated)).append(" à partir de l'historique git du coffre.\n\n")
        inputs.summary?.let { summary ->
            append("## Synthèse\n\n> Rédigée sur l'appareil (Gemini Nano), à vérifier.\n\n").append(summary.trim()).append("\n\n")
        }
        section("Notes modifiées pendant l'absence (${inputs.changed.size})", inputs.changed.map(::noteLine), "Aucune note de travail modifiée.")
        section("Nouvelles notes (${inputs.created.size})", inputs.created.map(::noteLine), "Aucune nouvelle note.")
        section("Journal de mission pendant l'absence", inputs.logEntries.map(::logLine), "Rien au journal pendant l'absence.")
        section("Échéances des 14 prochains jours", inputs.upcoming.map(::keyDateLine), "Aucune date clé à venir.")
    }

    /** A short French prompt for Gemini Nano: the list it summarizes, never more than [budget] characters. */
    fun summaryPrompt(task: String, facts: List<String>, budget: Int = 2_500): String = buildString {
        append(task).append("\n\nÉLÉMENTS :\n")
        for (fact in facts) {
            if (length + fact.length + 3 > budget) break
            append("- ").append(fact).append('\n')
        }
        append("\nRédige 5 phrases au maximum, factuelles, sans rien inventer ni citer de nom de personne. ")
        append(AiLanguage.DIRECTIVE)
    }

    fun handoverFacts(inputs: HandoverInputs): List<String> =
        inputs.inProgress.map { "En cours : ${it.title}" } +
            inputs.paused.map { "En pause : ${it.title}" } +
            inputs.decisions.map { "Décision : ${it.text}" } +
            inputs.openRequests.map { "Demande ouverte : ${it.text}" } +
            inputs.keyDates.map { "Échéance ${it.date} : ${it.label}" }

    fun briefingFacts(inputs: BriefingInputs): List<String> =
        inputs.changed.map { "Modifiée : ${it.title}" } +
            inputs.created.map { "Nouvelle : ${it.title}" } +
            inputs.logEntries.map { "${it.kind.word} : ${it.text}" }

    private fun StringBuilder.section(title: String, lines: List<String>, empty: String) {
        append("## ").append(title).append("\n\n")
        if (lines.isEmpty()) append("_").append(empty).append("_\n") else lines.forEach { append(it).append('\n') }
        append('\n')
    }

    private fun noteLine(note: WorkNote): String = buildString {
        append("- [[").append(note.linkName).append("]]")
        note.status?.let { append(" — ").append(it) }
        note.updated?.let { append(" · mis à jour le ").append(it) }
    }

    private fun logLine(entry: LogEntry): String = buildString {
        append("- ").append(entry.at.toLocalDate()).append(" — ").append(entry.text)
        val meta = listOfNotNull(
            entry.requester?.let { "demandé par : $it" },
            entry.decider?.let { "décidé par : $it" },
            entry.status?.let { "statut : $it" },
            entry.reference
        )
        if (meta.isNotEmpty()) append(" (").append(meta.joinToString(" · ")).append(')')
    }

    private fun keyDateLine(keyDate: KeyDate): String = "- ${keyDate.date} — ${keyDate.label}"
}
