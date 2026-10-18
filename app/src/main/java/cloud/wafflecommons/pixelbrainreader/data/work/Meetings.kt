package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A `- [ ] @owner text 📅 date` line. Mine when the owner is `@moi` / `@me`. */
data class ActionItem(
    val path: String,
    val text: String,
    val owner: String?,
    val done: Boolean,
    val due: LocalDate?,
    val sourceLine: String
) {
    val mine: Boolean get() = owner?.lowercase() in MeetingFormat.MY_HANDLES

    fun asMilestone() = Milestone(text, done, due, sourceLine)
}

/** A meeting note: its date, length, decisions and actions. */
data class Meeting(
    val note: WorkNote,
    val date: LocalDate?,
    val durationMinutes: Int?,
    val decisions: List<String>,
    val actions: List<ActionItem>
)

/** A week of load: meetings (and their minutes, when recorded), requests and off-ticket time from the log. */
data class WeekLoad(
    val weekStart: LocalDate,
    val meetings: Int,
    val meetingMinutes: Int?,
    val requests: Int,
    val offTicketMinutes: Int?
)

@Serializable
data class MeetingSettings(val folder: String? = null)

/**
 * Meetings & requests (#6): meeting notes from a French template, their decisions (which can go
 * to the mission log) and owned actions — the user's own (`@moi`) become next actions — and the
 * weekly load they add up to.
 */
object MeetingFormat {

    val MEETING_TAGS = setOf("reunion", "réunion", "meeting", "meetings", "reunions")
    val MY_HANDLES = setOf("moi", "me")
    private val DECISION_HEADINGS = setOf("décisions", "decisions", "décision", "decision")
    private val OWNER = Regex("""(?<![\p{L}\p{N}_])@([\p{L}\p{N}_.\-]+)""")
    private val DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")
    private val SPACES = Regex("""\s+""")

    fun isMeeting(note: WorkNote): Boolean = note.tags.any { it.lowercase().removePrefix("#") in MEETING_TAGS }

    fun defaultFolder(workFolders: List<String>): String = workFolders.firstOrNull()?.let { "$it/Réunions" } ?: "30_Areas/Réunions"

    fun path(folder: String, date: LocalDate, title: String): String =
        "${folder.trim('/')}/$date ${title.trim().replace('/', '-').replace('\\', '-')}.md"

    /** A new meeting note: R1 frontmatter (tag `reunion`, `duration_min`), the agenda, notes, decisions and actions sections. */
    fun newMeeting(path: String, title: String, date: LocalDate, start: LocalTime?, durationMinutes: Int?, participants: String?, today: LocalDate): String = buildString {
        val extra = listOfNotNull(durationMinutes?.takeIf { it > 0 }?.let { "duration_min" to it.toString() })
        append(VaultSchema.frontmatter(title = title, path = path, date = date.takeIf { it <= today } ?: today, tags = listOf("reunion"), extra = extra))
        append("# ").append(title).append("\n\n")
        append("**Date** : ").append(date)
        start?.let { append(" à ").append(it) }
        durationMinutes?.takeIf { it > 0 }?.let { append(" · ").append(it).append(" min") }
        participants?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · **Participants** : ").append(it) }
        append("\n\n## Ordre du jour\n\n- \n\n## Notes\n\n\n## Décisions\n\n- \n\n## Actions\n\n- [ ] @moi \n")
    }

    fun parse(note: WorkNote): Meeting {
        val date = DATE_PREFIX.find(note.path.substringAfterLast('/'))?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: note.created
        val duration = FrontmatterLines.get(note.content, "duration_min")?.toIntOrNull()
        return Meeting(note, date, duration, decisions(note.content), actions(note.path, note.content))
    }

    /** The list items under a "Décisions" heading, up to the next heading. */
    fun decisions(content: String): List<String> {
        val result = mutableListOf<String>()
        var inSection = false
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                inSection = trimmed.trimStart('#').trim().lowercase() in DECISION_HEADINGS
                continue
            }
            if (inSection && (trimmed.startsWith("- ") || trimmed.startsWith("* "))) {
                trimmed.drop(2).trim().takeIf { it.isNotEmpty() }?.let { result += it }
            }
        }
        return result
    }

    /** Every checklist item of [content] (outside code), with its owner. */
    fun actions(path: String, content: String): List<ActionItem> = ObjectiveFormat.milestones(content).map { m ->
        val owner = OWNER.find(m.text)?.groupValues?.get(1)
        val text = if (owner != null) m.text.replaceFirst("@$owner", "").replace(SPACES, " ").trim() else m.text
        ActionItem(path, text, owner, m.done, m.due, m.sourceLine)
    }.filter { it.text.isNotEmpty() }

    /** The last [weeks] weeks (Monday first), oldest first. Minutes are null when none was recorded. */
    fun weeklyLoad(meetings: List<Meeting>, log: List<LogEntry>, weeks: Int, today: LocalDate): List<WeekLoad> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (weeks - 1 downTo 0).map { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            val end = start.plusDays(6)
            val weekMeetings = meetings.filter { m -> m.date?.let { it in start..end } == true }
            val weekLog = log.filter { it.at.toLocalDate() in start..end }
            WeekLoad(
                weekStart = start,
                meetings = weekMeetings.size,
                meetingMinutes = weekMeetings.mapNotNull { it.durationMinutes }.takeIf { it.isNotEmpty() }?.sum(),
                requests = weekLog.count { it.kind == LogKind.REQUEST },
                offTicketMinutes = weekLog.filter { it.kind == LogKind.OFF_TICKET }.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()
            )
        }
    }
}
