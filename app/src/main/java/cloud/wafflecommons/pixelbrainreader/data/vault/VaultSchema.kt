package cloud.wafflecommons.pixelbrainreader.data.vault

import java.time.LocalDate

/**
 * The vault's note contract, in code: the type a folder implies, the default status of each type,
 * and the frontmatter every note the app creates must carry (the vault's `references/vault-schema.md`
 * and `ai-first-rules.md`, rules R1, R2 and R4). Notes written through here pass the desktop
 * linter; notes written around it used to be flagged on every phone capture.
 */
object VaultSchema {

    const val TYPE_INBOX = "inbox"
    const val TYPE_LOG = "log"
    const val TYPE_PROJECT = "projet"
    const val TYPE_AREA = "area"
    const val TYPE_RESOURCE = "ressource"
    const val TYPE_ARCHIVE = "archive"
    const val TYPE_PRIVATE = "private"
    const val TYPE_SYSTEM = "system"
    const val TYPE_NOTE = "note"

    val TYPES = setOf(
        TYPE_INBOX, TYPE_LOG, TYPE_PROJECT, TYPE_AREA, TYPE_RESOURCE,
        TYPE_ARCHIVE, TYPE_PRIVATE, TYPE_SYSTEM, TYPE_NOTE
    )

    /** R1: the standard keys, in the order the linter expects them. */
    val STANDARD_KEYS = listOf("title", "date_created", "date_updated", "type", "status", "tags", "aliases")

    const val INBOX_FOLDER = "00_Inbox"

    /** People & 1:1 notes: encrypted, one per person alias, never embedded or sent to the AI. */
    const val PEOPLE_FOLDER = "99_Private/People/"

    private val DAILY_NOTE = Regex("""^10_Journal/\d{4}-\d{2}-\d{2}\.md$""")
    private val WHITESPACE = Regex("""\s+""")

    /** The type the vault infers from the top-level folder of [path]. */
    fun typeFor(path: String): String {
        val top = path.trimStart('/').substringBefore('/')
        return when {
            top == "99_Private" -> TYPE_PRIVATE
            top == "99_System" -> TYPE_SYSTEM
            top.startsWith("00_") -> TYPE_INBOX
            top.startsWith("10_") -> TYPE_LOG
            top.startsWith("20_") -> TYPE_PROJECT
            top.startsWith("30_") -> TYPE_AREA
            top.startsWith("40_") -> TYPE_RESOURCE
            top.startsWith("90_") -> TYPE_ARCHIVE
            else -> TYPE_NOTE
        }
    }

    /** R4: the status a note of [type] starts with. */
    fun defaultStatus(type: String): String = when (type) {
        TYPE_INBOX -> "📥 inbox"
        TYPE_PROJECT -> "🏗️ wip"
        TYPE_ARCHIVE -> "📦 archive"
        else -> "🟢 done"
    }

    fun isPeopleNote(path: String): Boolean = path.startsWith(PEOPLE_FOLDER)

    /** R2: daily notes carry only `date:`. */
    fun isDailyNote(path: String): Boolean = DAILY_NOTE.matches(path)

    /** The vault's tag style: lower case, no `#`, no spaces. */
    fun normalizeTag(tag: String): String = tag.trim().removePrefix("#").lowercase().replace(WHITESPACE, "-")

    /**
     * An R1 frontmatter block, closing `---` and newline included: the standard keys in order (type
     * and default status inferred from [path]'s folder), then [extra] keys whose values are raw YAML
     * (quote them with [doubleQuoted] / [singleQuoted] when needed).
     */
    fun frontmatter(
        title: String,
        path: String,
        date: LocalDate,
        tags: List<String> = emptyList(),
        status: String? = null,
        extra: List<Pair<String, String>> = emptyList()
    ): String = buildString {
        val type = typeFor(path)
        append("---\n")
        append("title: ").append(doubleQuoted(title)).append('\n')
        append("date_created: ").append(singleQuoted(date.toString())).append('\n')
        append("date_updated: ").append(singleQuoted(date.toString())).append('\n')
        append("type: ").append(type).append('\n')
        append("status: ").append(status ?: defaultStatus(type)).append('\n')
        val cleanTags = tags.map(::normalizeTag).filter { it.isNotEmpty() }.distinct()
        if (cleanTags.isEmpty()) {
            append("tags: []\n")
        } else {
            append("tags:\n")
            cleanTags.forEach { append("  - ").append(it).append('\n') }
        }
        append("aliases: []\n")
        extra.forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        append("---\n")
    }

    /** A YAML double-quoted scalar on one line. */
    fun doubleQuoted(value: String): String {
        val oneLine = value.replace('\r', ' ').replace('\n', ' ').trim()
        return "\"" + oneLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** A YAML single-quoted scalar on one line (the vault's style for dates). */
    fun singleQuoted(value: String): String {
        val oneLine = value.replace('\r', ' ').replace('\n', ' ').trim()
        return "'" + oneLine.replace("'", "''") + "'"
    }
}
