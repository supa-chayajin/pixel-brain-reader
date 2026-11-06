package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Decision records (C9): a mission-log decision turned into a short French ADR, written next to
 * the log (in its `Décisions/` folder, encrypted when the log is) and linked back from the entry
 * (`[ref:: [[ADR …]]]`). Context, options and consequences are left for the user to fill in: the
 * log only knows the decision, its role, who decided and its reference.
 */
object DecisionRecords {

    const val FOLDER = "Décisions"
    private const val TITLE_CHARS = 60
    private val UNSAFE = Regex("""[\\/:*?"<>|\[\]#^]""")
    private val SPACES = Regex("""\s+""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun folder(settings: MissionLogSettings): String =
        (if (settings.encrypted) MissionLogSettings.ENCRYPTED_FOLDER else settings.folder.trim('/')) + "/" + FOLDER

    /** The decision's words, safe for a file name, cut at a word near [TITLE_CHARS]. */
    fun title(entry: LogEntry): String {
        val clean = entry.text.replace(UNSAFE, " ").replace(SPACES, " ").trim().trimEnd('.')
        if (clean.length <= TITLE_CHARS) return clean.ifEmpty { "Décision" }
        val cut = clean.take(TITLE_CHARS)
        return cut.substringBeforeLast(' ', cut).trim()
    }

    fun path(settings: MissionLogSettings, entry: LogEntry): String =
        "${folder(settings)}/ADR ${entry.at.toLocalDate()} ${title(entry)}.md" + if (settings.encrypted) ".enc" else ""

    /** `[[ADR 2026-11-06 Base choisie]]` for the note at [path]. */
    fun link(path: String): String = "[[" + path.substringAfterLast('/').removeSuffix(".enc").removeSuffix(".md") + "]]"

    /** The entry's reference with the note's link added (an existing ticket or link is kept). */
    fun reference(existing: String?, path: String): String =
        existing?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it · ${link(path)}" } ?: link(path)

    fun render(path: String, entry: LogEntry, today: LocalDate): String = buildString {
        val title = "ADR — ${title(entry)}"
        val tags = listOf("adr", "decision") + entry.tags.map(VaultSchema::normalizeTag).filter { it.isNotEmpty() }
        append(VaultSchema.frontmatter(title = title, path = path.removeSuffix(".enc"), date = today, tags = tags.distinct()))
        append("# ").append(title).append("\n\n")
        val meta = listOfNotNull(
            "**Date** : ${entry.at.toLocalDate()}",
            "**Statut** : ${entry.status ?: "acté"}",
            entry.role?.let { "**Rôle** : $it" },
            entry.decider?.let { "**Décidé par** : $it" }
        )
        append(meta.joinToString(" · ")).append("\n\n")
        append("## Contexte\n\n")
        entry.requester?.let { append("Demandé par : ").append(it).append(".\n\n") }
        append("- \n\n")
        append("## Options envisagées\n\n- \n- \n\n")
        append("## Décision\n\n").append(entry.text.trim()).append("\n\n")
        append("## Conséquences\n\n- \n\n")
        append("> Créée depuis le journal de mission (").append(entry.at.format(STAMP)).append(')')
        entry.reference?.takeIf { it.isNotBlank() }?.let { append(" · réf. : ").append(it) }
        append(".\n")
    }
}
