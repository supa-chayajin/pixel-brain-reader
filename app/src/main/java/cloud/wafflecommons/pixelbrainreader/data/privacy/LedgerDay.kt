package cloud.wafflecommons.pixelbrainreader.data.privacy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Why the app talked to a host (13.0, X3). The keys are persisted: never rename one. */
enum class NetPurpose(val key: String, val label: String) {
    SYNC("sync", "Vault sync"),
    SIGN_IN("sign_in", "GitHub sign-in"),
    CALENDAR("calendar", "Google Calendar import"),
    TASKS("tasks", "Google Tasks import"),
    WEATHER("weather", "Weather"),
    IMPORT("import", "Web page import"),
    /** The feeds' own reads (12.12); named "Feeds" like the tab since 13.4, its key kept. */
    WATCH("watch", "Feeds"),
    GAMES("games", "PlayStation sync"),
    MAPS("maps", "Game maps"),
    /** 13.3: the Xbox sign-in and its reads (Microsoft's sign-in, the Xbox network). */
    XBOX("xbox", "Xbox sync"),
    /** 13.4: the games' pictures from the consoles' image hosts, through the games image loader only. */
    GAME_PICTURES("game_pictures", "Game pictures"),
    /** 13.4 (F7): the feeds' thumbnails, from any public https host, through the feeds' image loader only. */
    FEED_PICTURES("feed_pictures", "Feed pictures"),
    OTHER("other", "Other");

    companion object {
        fun of(key: String): NetPurpose = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One host and purpose on one day: how many calls, and about how many bytes each way. */
@Serializable
data class LedgerCall(
    val host: String,
    val purpose: String,
    val calls: Int = 0,
    val sent: Long = 0,
    val received: Long = 0
)

/**
 * A day of the ledger, kept as `filesDir/privacy_ledger/<date>.json`: never a path, a query or
 * anything sent, only hosts, purposes, counts and bytes. [unnamedSent]/[unnamedReceived] are the
 * traffic the phone counted for the app while none of the named calls ran.
 */
@Serializable
data class LedgerDay(
    val date: String,
    val calls: List<LedgerCall> = emptyList(),
    @SerialName("unnamed_sent") val unnamedSent: Long = 0,
    @SerialName("unnamed_received") val unnamedReceived: Long = 0,
    /** False once this phone's traffic counter couldn't be read: the calls are counted, not their bytes. */
    val measured: Boolean = true
)

/** A host's share of a day, as the page lists it. */
data class HostTotal(val host: String, val purposes: List<NetPurpose>, val calls: Int, val sent: Long, val received: Long)

/** The ledger's pure side (X3): adding calls, grouping by host, which days stay. */
object LedgerDays {

    /** Days kept before today: "today first, then each of the last 30 days" (owner, D3). */
    const val DAYS_KEPT = 30

    fun add(day: LedgerDay, host: String, purpose: NetPurpose, sent: Long, received: Long, measured: Boolean = true): LedgerDay {
        val name = host.lowercase().ifBlank { "unknown" }
        val index = day.calls.indexOfFirst { it.host == name && it.purpose == purpose.key }
        val calls = if (index < 0) {
            day.calls + LedgerCall(name, purpose.key, 1, sent.coerceAtLeast(0), received.coerceAtLeast(0))
        } else {
            day.calls.toMutableList().also { list ->
                val old = list[index]
                list[index] = old.copy(
                    calls = old.calls + 1,
                    sent = old.sent + sent.coerceAtLeast(0),
                    received = old.received + received.coerceAtLeast(0)
                )
            }
        }
        return day.copy(calls = calls, measured = day.measured && measured)
    }

    /** [change], written since the last time, added to the [stored] day. */
    fun merge(stored: LedgerDay, change: LedgerDay): LedgerDay {
        val rows = stored.calls.toMutableList()
        change.calls.forEach { row ->
            val index = rows.indexOfFirst { it.host == row.host && it.purpose == row.purpose }
            if (index < 0) {
                rows += row
            } else {
                val old = rows[index]
                rows[index] = old.copy(calls = old.calls + row.calls, sent = old.sent + row.sent, received = old.received + row.received)
            }
        }
        return stored.copy(
            calls = rows,
            unnamedSent = stored.unnamedSent + change.unnamedSent,
            unnamedReceived = stored.unnamedReceived + change.unnamedReceived,
            measured = stored.measured && change.measured
        )
    }

    fun addUnnamed(day: LedgerDay, sent: Long, received: Long): LedgerDay =
        if (sent <= 0 && received <= 0) day
        else day.copy(unnamedSent = day.unnamedSent + sent.coerceAtLeast(0), unnamedReceived = day.unnamedReceived + received.coerceAtLeast(0))

    /** The day by host, the most bytes first; a host's purposes in the order the enum lists them. */
    fun byHost(day: LedgerDay): List<HostTotal> = day.calls
        .groupBy { it.host }
        .map { (host, rows) ->
            HostTotal(
                host = host,
                purposes = rows.map { NetPurpose.of(it.purpose) }.distinct().sortedBy { it.ordinal },
                calls = rows.sumOf { it.calls },
                sent = rows.sumOf { it.sent },
                received = rows.sumOf { it.received }
            )
        }
        .sortedWith(compareByDescending<HostTotal> { it.sent + it.received }.thenByDescending { it.calls }.thenBy { it.host })

    fun sent(day: LedgerDay): Long = day.calls.sumOf { it.sent } + day.unnamedSent

    fun received(day: LedgerDay): Long = day.calls.sumOf { it.received } + day.unnamedReceived

    fun isEmpty(day: LedgerDay): Boolean = day.calls.isEmpty() && day.unnamedSent == 0L && day.unnamedReceived == 0L

    /** True when a day's file is past the window and goes. */
    fun expired(date: LocalDate, today: LocalDate): Boolean = date.isBefore(today.minusDays(DAYS_KEPT.toLong()))

    /** "1.2 MB", "340 KB", "12 B": the page's bytes. */
    fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.ROOT, value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, value / 1_000_000.0)
        value >= 1_000 -> "${value / 1_000} KB"
        else -> "$value B"
    }
}
