package cloud.wafflecommons.pixelbrainreader.data.people

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Work or personal (12.19, owner's D1): a work person shows in Work mode only. Stored as `pro` or `perso`. */
enum class PersonKind(val word: String) {
    WORK("pro"),
    PERSONAL("perso");

    companion object {
        /** An unknown or missing kind counts as work: hidden outside Work mode rather than shown everywhere. */
        fun of(value: String?): PersonKind {
            val wanted = value?.trim()?.lowercase() ?: return WORK
            return entries.firstOrNull { it.word == wanted } ?: WORK
        }
    }
}

/** A checklist line: an open point or an action, the day it was added and, once ticked, the day it was done. */
data class CheckItem(
    val text: String,
    val added: LocalDate? = null,
    val done: Boolean = false,
    val doneOn: LocalDate? = null
)

/** Feedback given to the person ([given]) or received from them. */
data class Feedback(val date: LocalDate, val given: Boolean, val text: String)

/** One 1:1's entry in the person's log. */
data class OneOnOne(val date: LocalDate, val text: String)

/**
 * A person's encrypted note (12.19, P1; the owner's D8): role, context, open points, both sides'
 * actions, the 1:1 log, feedback and personal details. [kept] holds what the format doesn't know,
 * written back as it was.
 */
data class PersonNote(
    val name: String,
    val kind: PersonKind = PersonKind.WORK,
    val role: String = "",
    val created: LocalDate,
    val updated: LocalDate = created,
    val context: String = "",
    val openPoints: List<CheckItem> = emptyList(),
    val myActions: List<CheckItem> = emptyList(),
    val theirActions: List<CheckItem> = emptyList(),
    val oneOnOnes: List<OneOnOne> = emptyList(),
    val feedback: List<Feedback> = emptyList(),
    val personal: String = "",
    val kept: Kept = Kept()
) {
    /** Unknown frontmatter entries, unknown lines inside a known section (by heading), whole unknown sections. */
    data class Kept(
        val frontmatter: List<String> = emptyList(),
        val lines: Map<String, List<String>> = emptyMap(),
        val sections: List<Pair<String, String>> = emptyList(),
        val preamble: String = ""
    )

    val openPointsLeft: Int get() = openPoints.count { !it.done }
    val actionsLeft: Int get() = myActions.count { !it.done } + theirActions.count { !it.done }
    val lastOneOnOne: LocalDate? get() = oneOnOnes.maxOfOrNull { it.date }
}

/**
 * The People note format (pure). The sections are French, like the vault's other notes the app
 * writes; the frontmatter is the vault's DTO plus `kind` and `role`. A note only this app can read
 * (it is encrypted), so the format is strict on its own shapes and keeps anything else as it was.
 */
object PersonNoteFormat {

    const val CONTEXT = "Contexte"
    const val OPEN_POINTS = "Points ouverts"
    const val MY_ACTIONS = "Mes actions"
    const val THEIR_ACTIONS = "Ses actions"
    const val ONE_ON_ONES = "1:1"
    const val FEEDBACK = "Feedback"
    const val PERSONAL = "Personnel"

    private const val GIVEN = "donné"
    private const val RECEIVED = "reçu"

    /** The keys the format writes itself; any other frontmatter entry is kept as it was. */
    private val OWN_KEYS = setOf("title", "date_created", "date_updated", "type", "status", "tags", "aliases", "kind", "role")

    private val KEY = Regex("""^([A-Za-z_][A-Za-z0-9_-]*):(.*)$""")
    private val CHECK = Regex("""^- \[([ xX])\] (.*)$""")
    private val DONE_ON = Regex(""" ✅ (\d{4}-\d{2}-\d{2})$""")
    private val ADDED_ON = Regex(""" \((\d{4}-\d{2}-\d{2})\)$""")
    private val FEEDBACK_LINE = Regex("""^- (\d{4}-\d{2}-\d{2}) — ($GIVEN|$RECEIVED) : (.*)$""")
    private val HEADING_IN_TEXT = Regex("""^(#{1,3}) """)

    /** A fresh note for a person added today. */
    fun create(name: String, kind: PersonKind, role: String, today: LocalDate): PersonNote =
        PersonNote(name = oneLine(name), kind = kind, role = oneLine(role), created = today, updated = today)

    fun parse(text: String, fallbackDate: LocalDate): PersonNote {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        val front = linkedMapOf<String, MutableList<String>>()
        if (lines.firstOrNull()?.trim() == "---") {
            index = 1
            var current: String? = null
            while (index < lines.size && lines[index].trim() != "---") {
                val line = lines[index]
                val key = KEY.find(line)?.takeIf { !line.startsWith(" ") && !line.startsWith("\t") }
                if (key != null) {
                    current = key.groupValues[1]
                    front.getOrPut(current) { mutableListOf() }.add(line)
                } else if (current != null) {
                    front.getValue(current).add(line)
                }
                index++
            }
            index++ // the closing ---
        }
        fun value(key: String): String? = front[key]?.firstOrNull()?.substringAfter(':')?.let(::unquote)?.takeIf { it.isNotBlank() }

        // The body: an optional H1, then ## sections.
        val preamble = StringBuilder()
        var h1: String? = null
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("## ") -> sections += line.removePrefix("## ").trim() to mutableListOf()
                sections.isEmpty() && h1 == null && line.startsWith("# ") -> h1 = line.removePrefix("# ").trim()
                sections.isEmpty() -> preamble.append(line).append('\n')
                else -> sections.last().second += line
            }
            index++
        }

        var note = PersonNote(
            name = value("title") ?: h1 ?: "",
            kind = PersonKind.of(value("kind")),
            role = value("role").orEmpty(),
            created = date(value("date_created")) ?: fallbackDate,
            updated = date(value("date_updated")) ?: date(value("date_created")) ?: fallbackDate
        )
        val keptLines = linkedMapOf<String, List<String>>()
        val keptSections = mutableListOf<Pair<String, String>>()
        for ((heading, body) in sections) {
            when (heading) {
                CONTEXT -> note = note.copy(context = text(body))
                PERSONAL -> note = note.copy(personal = text(body))
                OPEN_POINTS, MY_ACTIONS, THEIR_ACTIONS -> {
                    val (items, rest) = checklist(body)
                    note = when (heading) {
                        OPEN_POINTS -> note.copy(openPoints = items)
                        MY_ACTIONS -> note.copy(myActions = items)
                        else -> note.copy(theirActions = items)
                    }
                    if (rest.isNotEmpty()) keptLines[heading] = rest
                }
                FEEDBACK -> {
                    val (items, rest) = feedback(body)
                    note = note.copy(feedback = items)
                    if (rest.isNotEmpty()) keptLines[heading] = rest
                }
                ONE_ON_ONES -> {
                    val (entries, rest) = oneOnOnes(body)
                    note = note.copy(oneOnOnes = entries)
                    if (rest.isNotEmpty()) keptLines[heading] = rest
                }
                else -> keptSections += heading to text(body)
            }
        }
        return note.copy(
            kept = PersonNote.Kept(
                frontmatter = front.filterKeys { it !in OWN_KEYS }.values.flatten(),
                lines = keptLines,
                sections = keptSections,
                preamble = preamble.toString().trim()
            )
        )
    }

    fun render(note: PersonNote): String = buildString {
        val path = VaultSchema.PEOPLE_FOLDER + "person.md.enc"
        val type = VaultSchema.typeFor(path)
        append("---\n")
        append("title: ").append(VaultSchema.doubleQuoted(note.name)).append('\n')
        append("date_created: ").append(VaultSchema.singleQuoted(note.created.toString())).append('\n')
        append("date_updated: ").append(VaultSchema.singleQuoted(note.updated.toString())).append('\n')
        append("type: ").append(type).append('\n')
        append("status: ").append(VaultSchema.defaultStatus(type)).append('\n')
        append("tags: []\n")
        append("aliases: []\n")
        append("kind: ").append(note.kind.word).append('\n')
        append("role: ").append(VaultSchema.doubleQuoted(note.role)).append('\n')
        note.kept.frontmatter.forEach { append(it).append('\n') }
        append("---\n")
        append("# ").append(oneLine(note.name)).append('\n')
        if (note.kept.preamble.isNotBlank()) append('\n').append(safeText(note.kept.preamble)).append('\n')

        section(CONTEXT, safeText(note.context))
        section(OPEN_POINTS, (note.openPoints.map(::checkLine) + note.kept.lines[OPEN_POINTS].orEmpty()).joinToString("\n"))
        section(MY_ACTIONS, (note.myActions.map(::checkLine) + note.kept.lines[MY_ACTIONS].orEmpty()).joinToString("\n"))
        section(THEIR_ACTIONS, (note.theirActions.map(::checkLine) + note.kept.lines[THEIR_ACTIONS].orEmpty()).joinToString("\n"))
        section(
            ONE_ON_ONES,
            (note.kept.lines[ONE_ON_ONES].orEmpty() + note.oneOnOnes.sortedByDescending { it.date }.map { entry ->
                "### ${entry.date}" + safeText(entry.text).let { if (it.isEmpty()) "" else "\n$it" }
            }).joinToString("\n\n")
        )
        section(
            FEEDBACK,
            (note.feedback.sortedByDescending { it.date }.map { "- ${it.date} — ${if (it.given) GIVEN else RECEIVED} : ${oneLine(it.text)}" } +
                note.kept.lines[FEEDBACK].orEmpty()).joinToString("\n")
        )
        section(PERSONAL, safeText(note.personal))
        note.kept.sections.forEach { (heading, body) -> section(heading, safeText(body)) }
    }

    private fun StringBuilder.section(heading: String, body: String) {
        append("\n## ").append(heading).append('\n')
        if (body.isNotBlank()) append(body.trimEnd()).append('\n')
    }

    private fun checkLine(item: CheckItem): String = buildString {
        append(if (item.done) "- [x] " else "- [ ] ").append(oneLine(item.text))
        item.added?.let { append(" (").append(it).append(')') }
        if (item.done) item.doneOn?.let { append(" ✅ ").append(it) }
    }

    private fun checklist(body: List<String>): Pair<List<CheckItem>, List<String>> {
        val items = mutableListOf<CheckItem>()
        val rest = mutableListOf<String>()
        for (line in body) {
            if (line.isBlank()) continue
            val match = CHECK.find(line)
            if (match == null) {
                rest += line
                continue
            }
            var text = match.groupValues[2].trimEnd()
            val doneOn = DONE_ON.find(text)?.let { m -> date(m.groupValues[1]).also { text = text.removeRange(m.range) } }
            val added = ADDED_ON.find(text)?.let { m -> date(m.groupValues[1]).also { text = text.removeRange(m.range) } }
            items += CheckItem(text.trim(), added, done = match.groupValues[1] != " ", doneOn = doneOn)
        }
        return items to rest
    }

    private fun feedback(body: List<String>): Pair<List<Feedback>, List<String>> {
        val items = mutableListOf<Feedback>()
        val rest = mutableListOf<String>()
        for (line in body) {
            if (line.isBlank()) continue
            val match = FEEDBACK_LINE.find(line)
            val day = match?.let { date(it.groupValues[1]) }
            if (match == null || day == null) rest += line else items += Feedback(day, match.groupValues[2] == GIVEN, match.groupValues[3].trim())
        }
        return items to rest
    }

    private fun oneOnOnes(body: List<String>): Pair<List<OneOnOne>, List<String>> {
        val entries = mutableListOf<Pair<LocalDate, MutableList<String>>>()
        val rest = mutableListOf<String>()
        for (line in body) {
            val day = if (line.startsWith("### ")) date(line.removePrefix("### ").trim()) else null
            when {
                day != null -> entries += day to mutableListOf()
                entries.isNotEmpty() -> entries.last().second += line
                line.isNotBlank() -> rest += line
            }
        }
        return entries.map { (day, lines) -> OneOnOne(day, text(lines)) } to rest
    }

    /** A section's free text: its lines, blank lines at both ends dropped. */
    private fun text(lines: List<String>): String = lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")

    /** Free text can't open a section: a heading inside it is lowered to level 4. */
    private fun safeText(text: String): String = text.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        HEADING_IN_TEXT.find(line)?.let { "####" + line.removePrefix(it.groupValues[1]) } ?: line
    }.trim()

    private fun oneLine(text: String): String = text.replace('\r', ' ').replace('\n', ' ').trim()

    private fun date(value: String?): LocalDate? = try {
        value?.trim()?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun unquote(raw: String): String {
        val value = raw.trim()
        return when {
            value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
                value.substring(1, value.length - 1).replace("''", "'")
            else -> value
        }
    }
}
