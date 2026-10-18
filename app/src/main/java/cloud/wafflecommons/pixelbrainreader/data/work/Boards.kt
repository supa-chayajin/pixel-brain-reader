package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** The folders shown as boards. Synced in the vault (`10_Journal/system/boards.json`). */
@Serializable
data class BoardsConfig(val boards: List<String> = emptyList())

/** A note on a board, with the card fields kept in its frontmatter (`next_step`, `blockers`, `target_date`). */
data class BoardCard(
    val note: WorkNote,
    val nextStep: String?,
    val blockers: String?,
    val targetDate: LocalDate?
) {
    fun overdue(today: LocalDate): Boolean = targetDate != null && targetDate < today && note.column != StatusColumn.DONE && note.column != StatusColumn.ARCHIVE

    companion object {
        fun of(note: WorkNote): BoardCard = BoardCard(
            note = note,
            nextStep = FrontmatterLines.get(note.content, KEY_NEXT_STEP)?.takeIf { it.isNotBlank() },
            blockers = FrontmatterLines.get(note.content, KEY_BLOCKERS)?.takeIf { it.isNotBlank() },
            targetDate = FrontmatterLines.get(note.content, KEY_TARGET_DATE)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        )

        const val KEY_NEXT_STEP = "next_step"
        const val KEY_BLOCKERS = "blockers"
        const val KEY_TARGET_DATE = "target_date"
    }
}

/**
 * Boards (#4): any folder's notes as cards in the vault's status columns. Every change edits the
 * note surgically — the moved key and `date_updated`, nothing else — so the vault's own linter and
 * mutator keep owning the rest of the frontmatter.
 */
object BoardFormat {

    /** Notes a board shows: markdown, not a daily note (those carry only `date:`, rule R2). */
    fun isCard(path: String): Boolean = path.endsWith(".md") && !VaultSchema.isDailyNote(path)

    /** A note without frontmatter gets the standard block first, so a card never writes a partial one. */
    fun withFrontmatter(path: String, content: String, today: LocalDate): String {
        if (FrontmatterLines.hasFrontmatter(content)) return content
        val title = content.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: path.substringAfterLast('/').removeSuffix(".md")
        return VaultSchema.frontmatter(title = title, path = path, date = today) + content.trimStart('\n')
    }

    fun move(path: String, content: String, column: StatusColumn, today: LocalDate): String =
        touch(FrontmatterLines.set(withFrontmatter(path, content, today), "status", column.vaultStatus), today)

    fun setDetails(path: String, content: String, nextStep: String?, blockers: String?, targetDate: LocalDate?, today: LocalDate): String {
        var updated = withFrontmatter(path, content, today)
        updated = setOrRemove(updated, BoardCard.KEY_NEXT_STEP, nextStep?.trim()?.takeIf { it.isNotEmpty() }?.let(VaultSchema::doubleQuoted))
        updated = setOrRemove(updated, BoardCard.KEY_BLOCKERS, blockers?.trim()?.takeIf { it.isNotEmpty() }?.let(VaultSchema::doubleQuoted))
        updated = setOrRemove(updated, BoardCard.KEY_TARGET_DATE, targetDate?.let { VaultSchema.singleQuoted(it.toString()) })
        return touch(updated, today)
    }

    /** The board as a French synthesis for a status meeting. */
    fun synthesis(folder: String, cards: List<BoardCard>, today: LocalDate): String = buildString {
        val name = LeaveDocuments.scopeName(folder)
        append(VaultSchema.frontmatter(title = "Synthèse — $name — $today", path = synthesisPath(folder, today), date = today, tags = listOf("synthese")))
        append("# Synthèse — ").append(name).append("\n\n")
        append("> Générée le ").append(today).append(" depuis le tableau `").append(folder).append("` (")
            .append(EvidenceReport.plural(cards.size, "carte")).append(").\n\n")
        append("| Colonne | Cartes |\n|---|---|\n")
        StatusColumn.entries.filter { it != StatusColumn.ARCHIVE }.forEach { column ->
            append("| ").append(column.frenchLabel).append(" | ").append(cards.count { it.note.column == column }).append(" |\n")
        }
        val unsorted = cards.count { it.note.column == null }
        if (unsorted > 0) append("| Sans statut | ").append(unsorted).append(" |\n")
        append('\n')
        cardSection("En cours", cards.filter { it.note.column == StatusColumn.WIP }, today)
        cardSection("En pause ou bloqué", cards.filter { it.note.column == StatusColumn.PAUSED || it.blockers != null && it.note.column == StatusColumn.WIP }.distinct(), today)
        cardSection("Échéances dépassées", cards.filter { it.overdue(today) }, today)
        cardSection(
            "Terminé ces 30 derniers jours",
            cards.filter { it.note.column == StatusColumn.DONE && it.note.updated?.let { d -> !d.isBefore(today.minusDays(30)) } == true },
            today
        )
    }

    fun synthesisPath(folder: String, today: LocalDate): String =
        "${VaultSchema.INBOX_FOLDER}/Synthèse ${LeaveDocuments.scopeName(folder)} $today.md"

    private fun StringBuilder.cardSection(title: String, cards: List<BoardCard>, today: LocalDate) {
        if (cards.isEmpty()) return
        append("## ").append(title).append("\n\n")
        cards.forEach { card ->
            append("- [[").append(card.note.linkName).append("]]")
            val details = listOfNotNull(
                card.nextStep?.let { "prochaine étape : $it" },
                card.blockers?.let { "blocages : $it" },
                card.targetDate?.let { "échéance : $it" + if (card.overdue(today)) " (dépassée)" else "" }
            )
            if (details.isNotEmpty()) append(" — ").append(details.joinToString(" · "))
            append('\n')
        }
        append('\n')
    }

    private fun touch(content: String, today: LocalDate): String =
        FrontmatterLines.set(content, "date_updated", VaultSchema.singleQuoted(today.toString()))

    private fun setOrRemove(content: String, key: String, rawValue: String?): String =
        if (rawValue == null) FrontmatterLines.remove(content, key) else FrontmatterLines.set(content, key, rawValue)
}
