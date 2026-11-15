package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.people.PersonKind
import cloud.wafflecommons.pixelbrainreader.data.people.PersonLink
import cloud.wafflecommons.pixelbrainreader.data.people.PersonLinks
import java.time.Duration
import java.time.LocalDateTime

/**
 * The 1:1 reminder (12.19, the owner's D6): 30 minutes before a meeting linked to a person (a link
 * made in its prep) or titled like a 1:1. Work people's meetings and 1:1 titles notify in Work mode
 * only, personal people's in any mode. The background can't read People notes (settled decision
 * #21): only the calendar's title and the phone's links decide. Pure.
 */
object OneOnOneReminders {

    val LEAD: Duration = Duration.ofMinutes(30)

    const val TAG = "one_on_one"

    /** Why a meeting gets the reminder. */
    enum class Reason { WORK_PERSON, PERSONAL_PERSON, TITLE }

    data class Due(val eventId: String, val delayMs: Long)

    // Whole words, any case; the boundaries written out (Android's regex engine is ICU, the tests run on the JVM).
    private val TITLE = Regex(
        """(?<![\p{L}\p{N}])(1 ?[:-] ?1|1on1|one[ -]on[ -]one|one[ -]to[ -]one|t[êe]te[ -][àa][ -]t[êe]te|point individuel|entretien individuel)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    fun isOneOnOneTitle(title: String): Boolean = TITLE.containsMatchIn(title)

    /** Why [entry] gets the reminder, or null when it doesn't. */
    fun reasonFor(entry: TimelineEntryEntity, links: Map<String, PersonLink>): Reason? {
        val eventId = entry.googleEventId ?: return null
        val link = PersonLinks.of(links, eventId)
        return when {
            link != null -> if (PersonKind.of(link.kind) == PersonKind.PERSONAL) Reason.PERSONAL_PERSON else Reason.WORK_PERSON
            isOneOnOneTitle(MeetingPrep.topic(entry.content)) -> Reason.TITLE
            else -> null
        }
    }

    /** Today's meetings that get the reminder and are still more than [LEAD] away, with the wait. */
    fun due(entries: List<TimelineEntryEntity>, links: Map<String, PersonLink>, now: LocalDateTime): List<Due> = entries.mapNotNull { entry ->
        val eventId = entry.googleEventId ?: return@mapNotNull null
        reasonFor(entry, links) ?: return@mapNotNull null
        val delay = Duration.between(now, LocalDateTime.of(entry.date, entry.time).minus(LEAD)).toMillis()
        if (delay <= 0) null else Due(eventId, delay)
    }

    /** Whether the reminder posts in the current mode: a personal person's meeting in any mode, the rest in Work mode. */
    fun posts(reason: Reason, muteWorkNotifications: Boolean): Boolean = reason == Reason.PERSONAL_PERSON || !muteWorkNotifications

    /** One unique work per calendar event: a new import replaces it, never doubles it. */
    fun workName(eventId: String): String = "${TAG}_$eventId"
}
