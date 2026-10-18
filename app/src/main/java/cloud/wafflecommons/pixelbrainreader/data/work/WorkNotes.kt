package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import java.time.LocalDate

/**
 * The vault's status vocabulary as board columns. [vaultStatus] is what a move writes; [of] also
 * reads the older and hand-written statuses found in the vault ("active", "🏗️ en cours", "draft"…).
 */
enum class StatusColumn(val vaultStatus: String, val label: String, val frenchLabel: String) {
    TODO("📥 inbox", "To do", "À faire"),
    WIP("🏗️ wip", "In progress", "En cours"),
    PAUSED("⏸️ en pause", "Paused", "En pause"),
    DONE("🟢 done", "Done", "Terminé"),
    ARCHIVE("📦 archive", "Archived", "Archivé");

    companion object {
        private val TODO_WORDS = listOf("inbox", "todo", "to do", "à faire", "draft", "idée", "idea", "backlog")
        private val WIP_WORDS = listOf("wip", "active", "en cours", "in progress", "doing", "proposal", "ready", "à relire", "review", "à revérifier")
        private val PAUSED_WORDS = listOf("pause", "blocked", "bloqué", "en attente", "waiting", "on hold")
        private val DONE_WORDS = listOf("done", "réalisé", "terminé", "validated", "validé", "evergreen", "publié", "référence", "fini", "livré")
        private val ARCHIVE_WORDS = listOf("archive", "remplacé", "historique", "obsolète", "abandonné")

        /** The column a status belongs to, or null when it says nothing a board can use. */
        fun of(status: String?): StatusColumn? {
            val s = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                s.startsWith("📥") || s.startsWith("💡") -> TODO
                s.startsWith("🏗") || s.startsWith("🔄") || s.startsWith("🟡") -> WIP
                s.startsWith("⏸") || s.startsWith("🔴") || s.startsWith("⛔") -> PAUSED
                s.startsWith("🟢") || s.startsWith("✅") -> DONE
                s.startsWith("📦") -> ARCHIVE
                ARCHIVE_WORDS.any { it in s } -> ARCHIVE
                PAUSED_WORDS.any { it in s } -> PAUSED
                DONE_WORDS.any { it in s } -> DONE
                WIP_WORDS.any { it in s } -> WIP
                TODO_WORDS.any { it in s } -> TODO
                else -> null
            }
        }
    }
}

/** A vault note as the work tools read it: frontmatter facts plus its text. */
data class WorkNote(
    val path: String,
    val title: String,
    val status: String?,
    val updated: LocalDate?,
    val created: LocalDate?,
    val tags: List<String>,
    val content: String
) {
    val column: StatusColumn? get() = StatusColumn.of(status)

    /** For a `[[wikilink]]`: the file name without `.md`. */
    val linkName: String get() = path.substringAfterLast('/').removeSuffix(".md")

    companion object {
        fun from(path: String, content: String): WorkNote {
            val title = FrontmatterLines.get(content, "title")?.takeIf { it.isNotBlank() }
                ?: content.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: path.substringAfterLast('/').removeSuffix(".md")
            return WorkNote(
                path = path,
                title = title,
                status = FrontmatterLines.get(content, "status"),
                updated = FrontmatterLines.get(content, "date_updated")?.let(::dateOrNull),
                created = FrontmatterLines.get(content, "date_created")?.let(::dateOrNull),
                tags = FrontmatterLines.getList(content, "tags"),
                content = content
            )
        }

        private fun dateOrNull(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }
}
