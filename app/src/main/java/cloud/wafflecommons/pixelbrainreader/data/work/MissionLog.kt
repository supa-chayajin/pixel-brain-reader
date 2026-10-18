package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What a mission-log entry records. [word] is the vault value (French), [label] the UI's. */
enum class LogKind(val word: String, val label: String) {
    FACT("fait", "Fact"),
    DECISION("décision", "Decision"),
    REQUEST("demande", "Request"),
    REFUSAL("refus", "Refusal"),
    OFF_TICKET("hors-ticket", "Off-ticket time");

    companion object {
        private val ALIASES = mapOf(
            "decision" to DECISION, "fact" to FACT, "request" to REQUEST, "refusal" to REFUSAL,
            "off-ticket" to OFF_TICKET, "hors ticket" to OFF_TICKET, "horsticket" to OFF_TICKET
        )

        fun of(value: String): LogKind? {
            val v = value.trim().lowercase()
            return entries.firstOrNull { it.word == v } ?: ALIASES[v]
        }
    }
}

/**
 * One line of the mission log: a fact, a decision, a request, a refusal or off-ticket time, with
 * the role the user held, a status label, minutes, who asked and who decided (by role, never by
 * name), a reference and tags. [sourceLine] is the line as read, so an edit replaces exactly it.
 */
data class LogEntry(
    val at: LocalDateTime,
    val kind: LogKind,
    val text: String,
    val role: String? = null,
    val status: String? = null,
    val minutes: Int? = null,
    val requester: String? = null,
    val decider: String? = null,
    val reference: String? = null,
    val tags: List<String> = emptyList(),
    val sourceLine: String? = null
)

/** Where the log lives and the roles offered when capturing. Synced in the vault. */
@Serializable
data class MissionLogSettings(
    val folder: String = DEFAULT_FOLDER,
    val encrypted: Boolean = false,
    val roles: List<String> = DEFAULT_ROLES
) {
    companion object {
        const val DEFAULT_FOLDER = "30_Areas/Mission log"
        const val ENCRYPTED_FOLDER = "99_Private/Mission log"
        val DEFAULT_ROLES = listOf("Lead", "DevSecOps", "Architecture", "Delivery", "Support")
    }
}

/**
 * The log's markdown: one file per month, one list item per entry, its fields as Dataview inline
 * fields so they can be queried in Obsidian:
 *
 * `- 2026-10-18 09:40 — Base choisie pour le service [type:: décision] [role:: Lead] [statut:: acté] [minutes:: 30] [demandeur:: PO] [decideur:: CTO] [ref:: ABC-123] #infra`
 *
 * Lines that aren't entries (the title, the user's own notes) are kept as they are.
 */
object MissionLogFormat {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val ENTRY = Regex("""^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) [—–\-] (.*)$""")
    private val FIELD = Regex("""\[([\p{L}_ ]+)::\s*([^\]]*)\]""")
    private val TAG = Regex("""(?<![\p{L}\p{N}_/])#(\p{L}[\p{L}\p{N}_/\-]*)""")
    private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")
    private val SPACES = Regex("""\s+""")

    fun fileName(month: YearMonth, encrypted: Boolean): String = "$month.md" + if (encrypted) ".enc" else ""

    fun pathFor(settings: MissionLogSettings, month: YearMonth): String {
        val folder = if (settings.encrypted) MissionLogSettings.ENCRYPTED_FOLDER else settings.folder.trim('/')
        return "$folder/${fileName(month, settings.encrypted)}"
    }

    /** A new month's file: frontmatter, a heading, then the entries. */
    fun newFile(path: String, month: YearMonth, today: LocalDate): String = buildString {
        append(VaultSchema.frontmatter(title = "Journal de mission $month", path = path.removeSuffix(".enc"), date = today, tags = listOf("mission-log")))
        val monthName = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.FRENCH)
        append("# Journal de mission — ").append(monthName).append(' ').append(month.year).append("\n\n")
    }

    fun render(entry: LogEntry): String = buildString {
        append("- ").append(entry.at.format(STAMP)).append(" — ").append(entry.text.singleLine())
        append(" [type:: ").append(entry.kind.word).append(']')
        entry.role.field("role")?.let(::append)
        entry.status.field("statut")?.let(::append)
        entry.minutes?.takeIf { it > 0 }?.let { append(" [minutes:: ").append(it).append(']') }
        entry.requester.field("demandeur")?.let(::append)
        entry.decider.field("decideur")?.let(::append)
        entry.reference.field("ref")?.let(::append)
        entry.tags.map(VaultSchema::normalizeTag).filter { it.isNotEmpty() }.distinct().forEach { append(" #").append(it) }
    }

    fun parse(content: String): List<LogEntry> = content.lines().mapNotNull(::parseLine)

    fun parseLine(line: String): LogEntry? {
        val match = ENTRY.matchEntire(line.trimEnd()) ?: return null
        val at = runCatching { LocalDateTime.parse(match.groupValues[1], STAMP) }.getOrNull() ?: return null
        val rest = match.groupValues[2]
        val fields = FIELD.findAll(rest).associate { it.groupValues[1].trim().lowercase() to it.groupValues[2].trim() }
        val kind = (fields["type"] ?: fields["kind"])?.let(LogKind::of) ?: return null
        val withoutFields = FIELD.replace(rest, "")
        val tags = TAG.findAll(withoutFields).map { it.groupValues[1] }.toList()
        val text = TAG.replace(withoutFields, "").replace(SPACES, " ").trim()
        fun field(vararg keys: String) = keys.firstNotNullOfOrNull { fields[it] }?.takeIf { it.isNotBlank() }
        return LogEntry(
            at = at,
            kind = kind,
            text = text,
            role = field("role", "rôle"),
            status = field("statut", "status"),
            minutes = field("minutes", "min")?.toIntOrNull(),
            requester = field("demandeur", "requester", "demandé_par"),
            decider = field("decideur", "décideur", "decider", "décidé_par"),
            reference = field("ref", "réf", "reference"),
            tags = tags,
            sourceLine = line
        )
    }

    /** [content] with [entry] added in time order (after the last entry not later than it). */
    fun insert(content: String, entry: LogEntry): String {
        val lines = content.trimEnd('\n').lines().toMutableList()
        val line = render(entry)
        val after = lines.indexOfLast { existing -> parseLine(existing)?.let { !it.at.isAfter(entry.at) } == true }
        val firstLater = lines.indexOfFirst { existing -> parseLine(existing)?.at?.isAfter(entry.at) == true }
        when {
            after >= 0 -> lines.add(after + 1, line)
            firstLater >= 0 -> lines.add(firstLater, line)
            else -> {
                if (lines.lastOrNull()?.isNotBlank() == true && parseLine(lines.last()) == null) lines += ""
                lines += line
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    /** [content] with the line [old] was read from replaced by [replacement], or removed when null. */
    fun replace(content: String, old: LogEntry, replacement: LogEntry?): String? {
        val source = old.sourceLine ?: return null
        val lines = content.lines().toMutableList()
        val index = lines.indexOf(source)
        if (index < 0) return null
        if (replacement == null) lines.removeAt(index) else lines[index] = render(replacement)
        return lines.joinToString("\n")
    }

    private fun String.singleLine(): String = replace(LINE_BREAKS, " ").trim()

    /** A field value can't hold brackets: they would end the field. */
    private fun String?.field(key: String): String? =
        this?.singleLine()?.replace('[', '(')?.replace(']', ')')?.takeIf { it.isNotBlank() }?.let { " [$key:: $it]" }
}
