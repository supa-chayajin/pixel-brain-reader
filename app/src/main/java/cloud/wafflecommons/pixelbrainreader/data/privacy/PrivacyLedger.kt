package cloud.wafflecommons.pixelbrainreader.data.privacy

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * "What left this phone today" (13.0, X3): each host the app talked to, why, how often and about
 * how many bytes, never what was sent. Kept on the phone only (`filesDir/privacy_ledger`, outside
 * the vault, no backup) for today and the last 30 days.
 *
 * The bytes come from the phone's own counter for the app, read as each call starts and ends
 * ([TrafficAccounts]), so they include encryption and headers, and calls at the same moment share
 * what was measured. What the phone counted while no named call ran is the day's unnamed traffic.
 *
 * A global switch like `VaultWriteGate`, installed by the application before anything talks to the
 * network; until then (and in unit tests) every call is a no-op.
 */
object PrivacyLedger {

    /** A change is written this long after the last one, with whatever followed it. */
    private const val FLUSH_DELAY_MS = 5_000L

    private class State(
        val dir: File,
        val scope: CoroutineScope,
        val accounts: TrafficAccounts,
        val today: () -> LocalDate
    ) {
        val lock = Any()
        /** What happened since the last write, per day. */
        val pending = LinkedHashMap<String, LedgerDay>()
        val writing = Mutex()
        @Volatile var flushScheduled = false
        var prunedFor: LocalDate? = null
    }

    @Volatile
    private var state: State? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Starts the ledger in the app's main process, before any network call. */
    fun install(context: Context, scope: CoroutineScope) {
        if (state != null) return
        install(
            dir = File(context.filesDir, "privacy_ledger"),
            scope = scope,
            counter = UidCounter(),
            elapsed = SystemClock::elapsedRealtime,
            today = LocalDate::now
        )
    }

    internal fun install(dir: File, scope: CoroutineScope, counter: TrafficCounter, elapsed: () -> Long, today: () -> LocalDate) {
        state = State(dir, scope, TrafficAccounts(counter, elapsed), today)
    }

    /** Tests only: the ledger off again. */
    internal fun uninstall() {
        state = null
    }

    /** A named call starts; the token ends it. 0 while the ledger is off. */
    fun begin(host: String, purpose: NetPurpose): Long = begin(host, purpose, expires = false)

    private fun begin(host: String, purpose: NetPurpose, expires: Boolean): Long =
        state?.accounts?.begin(host, purpose, expires) ?: 0L

    fun end(token: Long) {
        if (token == 0L) return
        val s = state ?: return
        val call = s.accounts.end(token) ?: return
        synchronized(s.lock) { note(s, call) }
        scheduleFlush(s)
    }

    /**
     * Runs [block] as one call to [host] for [purpose]: its traffic is counted, whatever it throws.
     * Inline, so a suspending block and an early return inside it work as they would without it.
     */
    inline fun <T> measured(host: String, purpose: NetPurpose, block: () -> T): T {
        val token = begin(host, purpose)
        try {
            return block()
        } finally {
            end(token)
        }
    }

    /**
     * An OkHttp client's calls in the ledger, each from its start to its end. [purpose] null: the
     * shared client, whose hosts say what they are for.
     */
    fun events(purpose: NetPurpose?): EventListener.Factory = EventListener.Factory { LedgerListener(purpose) }

    /** The host of [url] (never its path or query), or [fallback] when it has none. */
    fun hostOf(url: String?, fallback: String = "unknown"): String =
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.lowercase() ?: fallback

    /** The purpose of a call on the shared client, from its host. */
    internal fun purposeOf(host: String): NetPurpose = when {
        host.endsWith("open-meteo.com") -> NetPurpose.WEATHER
        host == "github.com" || host.endsWith(".github.com") -> NetPurpose.SIGN_IN
        else -> NetPurpose.OTHER
    }

    /** Writes what is pending soon: when the app leaves the screen, say. */
    fun checkpoint() {
        state?.let(::scheduleFlush)
    }

    /** Today first (even empty), then each earlier day of the window that has a file, newest first. */
    suspend fun days(): List<LedgerDay> {
        val s = state ?: return emptyList()
        flush(s)
        return withContext(Dispatchers.IO) {
            val today = s.today()
            val files = s.dir.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
            val earlier = files.mapNotNull { file ->
                val date = runCatching { LocalDate.parse(file.name.removeSuffix(".json")) }.getOrNull() ?: return@mapNotNull null
                if (date >= today || LedgerDays.expired(date, today)) null else read(file, date)
            }.sortedByDescending { it.date }
            val todays = read(File(s.dir, "$today.json"), today) ?: LedgerDay(today.toString())
            listOf(todays) + earlier
        }
    }

    private fun note(s: State, call: ClosedCall) {
        val date = s.today().toString()
        val day = s.pending[date] ?: LedgerDay(date)
        s.pending[date] = LedgerDays.add(day, call.host, call.purpose, call.sent, call.received, call.measured)
    }

    private fun scheduleFlush(s: State) {
        if (s.flushScheduled) return
        s.flushScheduled = true
        s.scope.launch {
            delay(FLUSH_DELAY_MS)
            s.flushScheduled = false
            try {
                flush(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                android.util.Log.w("PrivacyLedger", "The ledger couldn't be written: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Folds the unnamed traffic and what is pending into each day's file. */
    private suspend fun flush(s: State) = s.writing.withLock {
        withContext(Dispatchers.IO) {
            val (pool, late) = s.accounts.take()
            val batch: Map<String, LedgerDay>
            synchronized(s.lock) {
                late.forEach { note(s, it) }
                val date = s.today().toString()
                val day = s.pending[date] ?: LedgerDay(date)
                val withPool = LedgerDays.addUnnamed(day, pool.sent, pool.received)
                if (!LedgerDays.isEmpty(withPool) || s.pending.containsKey(date)) s.pending[date] = withPool
                batch = s.pending.toMap()
                s.pending.clear()
            }
            s.dir.mkdirs()
            batch.forEach { (date, change) ->
                val file = File(s.dir, "$date.json")
                val stored = read(file, LocalDate.parse(date)) ?: LedgerDay(date)
                write(file, LedgerDays.merge(stored, change))
            }
            prune(s)
        }
    }

    private fun prune(s: State) {
        val today = s.today()
        if (s.prunedFor == today) return
        s.prunedFor = today
        s.dir.listFiles().orEmpty().forEach { file ->
            val date = runCatching { LocalDate.parse(file.name.substringBefore('.')) }.getOrNull()
            if (date != null && LedgerDays.expired(date, today)) file.delete()
        }
    }

    /** A day's file; one that no longer parses is set aside, and the day starts again. */
    private fun read(file: File, date: LocalDate): LedgerDay? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(LedgerDay.serializer(), file.readText()).takeIf { it.date == date.toString() }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            file.renameTo(File(file.parentFile, file.name + ".bad"))
            null
        }
    }

    private fun write(file: File, day: LedgerDay) {
        val staging = File(file.parentFile, file.name + ".tmp")
        staging.writeText(json.encodeToString(LedgerDay.serializer(), day))
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IOException("rename failed")
        }
    }

    private class LedgerListener(private val purpose: NetPurpose?) : EventListener() {
        @Volatile
        private var token = 0L

        override fun callStart(call: Call) {
            val host = call.request().url.host
            token = begin(host, purpose ?: purposeOf(host), expires = true)
        }

        override fun callEnd(call: Call) = finish()

        override fun callFailed(call: Call, ioe: IOException) = finish()

        private fun finish() {
            val ended = token
            token = 0L
            end(ended)
        }
    }

    /** The phone's own count of the app's traffic since it started (eBPF-backed, read as a whole). */
    private class UidCounter : TrafficCounter {
        private val uid = android.os.Process.myUid()

        override fun read(): TrafficCount? {
            val sent = TrafficStats.getUidTxBytes(uid)
            val received = TrafficStats.getUidRxBytes(uid)
            val unsupported = TrafficStats.UNSUPPORTED.toLong()
            return if (sent == unsupported || received == unsupported) null else TrafficCount(sent, received)
        }
    }
}
