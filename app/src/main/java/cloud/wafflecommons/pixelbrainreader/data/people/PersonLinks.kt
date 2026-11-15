package cloud.wafflecommons.pixelbrainreader.data.people

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A calendar meeting's series (12.19, the owner's D5): the import fetches single events, and Google
 * names a recurring event's instance `<series id>_<start>` (its ids are base32hex, no `_` of their
 * own), so the part before `_` is the series. A single event's id is its own series. A link made on
 * one week's meeting then follows every week's, with no database column and nothing in the daily
 * note (settled decision #7). Pure.
 */
object MeetingSeries {
    fun keyOf(eventId: String): String = eventId.substringBefore('_').ifEmpty { eventId }
}

/** The person a meeting series is linked to: their neutral id and whether they're work or personal. */
@Serializable
data class PersonLink(val person: String, val kind: String)

/**
 * The links between meeting series and people (12.19, D5), kept on the phone only
 * (`UserPreferencesRepository.personLinks`): neutral ids, never a name. The 1:1 reminder reads them
 * in the background, where People notes can't be read. Pure.
 */
object PersonLinks {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PersonLink.serializer())

    /** The links by series key; an empty map for nothing stored or a value that doesn't parse. */
    fun decode(stored: String?): Map<String, PersonLink> = try {
        stored?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    fun encode(links: Map<String, PersonLink>): String = json.encodeToString(serializer, links)

    fun link(stored: String?, series: String, person: String, kind: PersonKind): String =
        encode(decode(stored) + (series to PersonLink(person, kind.word)))

    fun unlink(stored: String?, series: String): String = encode(decode(stored) - series)

    /** Every link to [person] gone (their note was deleted). */
    fun forget(stored: String?, person: String): String = encode(decode(stored).filterValues { it.person != person })

    /** The link of the meeting whose calendar event is [eventId], if its series is linked. */
    fun of(links: Map<String, PersonLink>, eventId: String): PersonLink? = links[MeetingSeries.keyOf(eventId)]
}
