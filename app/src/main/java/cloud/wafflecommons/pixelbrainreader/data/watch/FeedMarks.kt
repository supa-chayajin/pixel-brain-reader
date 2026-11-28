package cloud.wafflecommons.pixelbrainreader.data.watch

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's read marks (13.4, F3), stored as JSON; the field names are persisted. [readUpTo]:
 * per feed address, every headline first seen at or before that time is read ("Mark all read").
 * [read]: headlines opened or imported one by one. [tabOpenedAt]: the Feeds tab's last opening
 * (its dot).
 */
@Serializable
data class FeedMarks(
    @SerialName("read_upto") val readUpTo: Map<String, Long> = emptyMap(),
    val read: Set<String> = emptySet(),
    @SerialName("tab_opened_at") val tabOpenedAt: Long = 0L
)

/** The marks' rules (13.4, F3), pure. */
object FeedMarkRules {

    private val json = Json { ignoreUnknownKeys = true }

    /** Read when opened one by one, or first seen at or before its feed's "Mark all read". */
    fun isRead(marks: FeedMarks, url: String, feedUrl: String, firstSeenAt: Long): Boolean =
        url in marks.read || firstSeenAt <= (marks.readUpTo[feedUrl] ?: Long.MIN_VALUE)

    /** [url] opened (in the reader or the browser) or imported. */
    fun markRead(marks: FeedMarks, url: String): FeedMarks =
        if (url in marks.read) marks else marks.copy(read = marks.read + url)

    /**
     * Each feed's mark moves to its newest firstSeenAt among [entries], never back; their addresses
     * leave `read` (the marks cover them now). A headline that arrives later stays unread, even one
     * published earlier.
     */
    fun markAllRead(marks: FeedMarks, entries: List<FeedEntry>): FeedMarks {
        if (entries.isEmpty()) return marks
        val upTo = marks.readUpTo.toMutableMap()
        entries.groupBy { it.feedUrl }.forEach { (feed, mine) ->
            val newest = mine.maxOf { it.firstSeenAt }
            upTo[feed] = maxOf(upTo[feed] ?: Long.MIN_VALUE, newest)
        }
        val covered = entries.mapTo(HashSet()) { it.url }
        return marks.copy(readUpTo = upTo, read = marks.read.filterTo(LinkedHashSet()) { it !in covered })
    }

    /** The tab opened (or left) at [now]: its time moves forward only. */
    fun opened(marks: FeedMarks, now: Long): FeedMarks =
        if (now <= marks.tabOpenedAt) marks else marks.copy(tabOpenedAt = now)

    /** Drops the single reads of addresses no longer cached and the marks of feeds no longer listed. */
    fun prune(marks: FeedMarks, cachedUrls: Set<String>, feedUrls: Set<String>): FeedMarks {
        val read = marks.read.filterTo(LinkedHashSet()) { it in cachedUrls }
        val upTo = marks.readUpTo.filterKeys { it in feedUrls }
        return if (read == marks.read && upTo == marks.readUpTo) marks else marks.copy(readUpTo = upTo, read = read)
    }

    fun encode(marks: FeedMarks): String = json.encodeToString(FeedMarks.serializer(), marks)

    /** Blank, absent or unreadable: no mark (everything unread), never a crash. */
    fun decode(raw: String?): FeedMarks =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(FeedMarks.serializer(), it) }.getOrNull() }
            ?: FeedMarks()
}

/**
 * Its own file, not `user_preferences`: the marks change at every opened headline, and every
 * unrelated setting's write would rewrite them there. A file the device can't read is replaced
 * with no marks rather than failing every later write.
 */
private val Context.feedMarksStore: DataStore<Preferences> by preferencesDataStore(
    name = "feed_marks",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** This device's read marks (13.4, F3): never the vault, so a read-only device keeps its own. */
@Singleton
class FeedMarksStore @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** The marks as stored; an unreadable store is no mark (as UserPreferencesRepository does). */
    val marks: Flow<FeedMarks> = context.feedMarksStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "Feed marks unreadable; none used: ${e.javaClass.simpleName}")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { FeedMarkRules.decode(it[KEY_MARKS]) }
        .distinctUntilChanged()

    /**
     * One DataStore edit: [change] applied to the stored marks, written only when it changes them;
     * the marks now stored. A write the disk refuses keeps the marks it had.
     */
    suspend fun update(change: (FeedMarks) -> FeedMarks): FeedMarks = try {
        val stored = context.feedMarksStore.edit { prefs ->
            val current = FeedMarkRules.decode(prefs[KEY_MARKS])
            val next = change(current)
            if (next != current) prefs[KEY_MARKS] = FeedMarkRules.encode(next)
        }
        FeedMarkRules.decode(stored[KEY_MARKS])
    } catch (e: IOException) {
        Log.w(TAG, "Feed marks couldn't be saved: ${e.javaClass.simpleName}")
        marks.first()
    }

    private companion object {
        const val TAG = "FeedMarks"
        val KEY_MARKS = stringPreferencesKey("marks")
    }
}
