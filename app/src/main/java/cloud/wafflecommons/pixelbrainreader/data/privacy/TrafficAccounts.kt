package cloud.wafflecommons.pixelbrainreader.data.privacy

/** The app's own traffic since the phone started, both ways; null when the phone can't say. */
data class TrafficCount(val sent: Long, val received: Long)

fun interface TrafficCounter {
    fun read(): TrafficCount?
}

/** A named call once it ended, with its share of what the counter measured while it ran. */
data class ClosedCall(val host: String, val purpose: NetPurpose, val sent: Long, val received: Long, val measured: Boolean)

/**
 * Splits what the phone counts for the app between the calls running at each moment (X3). Each
 * start and end reads the counter: what it measured since the last read goes to the calls open
 * then, shared equally, or to the unnamed pool when none was. A byte is counted once, whatever
 * runs at the same time, so the calls and the pool add up to what the phone counted.
 */
internal class TrafficAccounts(private val counter: TrafficCounter, private val now: () -> Long) {

    private class Open(val host: String, val purpose: NetPurpose, val startedAt: Long, val expires: Boolean) {
        var sent = 0L
        var received = 0L
    }

    private val open = LinkedHashMap<Long, Open>()
    private var nextId = 1L
    private var last: TrafficCount? = counter.read()
    private var unnamedSent = 0L
    private var unnamedReceived = 0L
    private val expired = mutableListOf<ClosedCall>()

    /** True while the phone's counter answers. */
    val measured: Boolean get() = last != null

    /**
     * A call starts; [expires] for one whose end may never be reported (an HTTP body never closed):
     * past [MAX_OPEN_MS] it is closed with what it had.
     */
    @Synchronized
    fun begin(host: String, purpose: NetPurpose, expires: Boolean = false): Long {
        advance()
        val id = nextId++
        open[id] = Open(host, purpose, now(), expires)
        return id
    }

    /** The call [id] ended; null when it was already closed. */
    @Synchronized
    fun end(id: Long): ClosedCall? {
        advance()
        return open.remove(id)?.let { closed(it) }
    }

    /** What ran with no named call since the last take, and the calls closed for running too long. */
    @Synchronized
    fun take(): Pair<TrafficCount, List<ClosedCall>> {
        advance()
        val pool = TrafficCount(unnamedSent, unnamedReceived)
        unnamedSent = 0
        unnamedReceived = 0
        val late = expired.toList()
        expired.clear()
        return pool to late
    }

    private fun closed(call: Open) = ClosedCall(call.host, call.purpose, call.sent, call.received, measured)

    private fun advance() {
        val reading = counter.read()
        val previous = last
        last = reading
        if (reading != null && previous != null) {
            val sent = (reading.sent - previous.sent).coerceAtLeast(0)
            val received = (reading.received - previous.received).coerceAtLeast(0)
            if (open.isEmpty()) {
                unnamedSent += sent
                unnamedReceived += received
            } else {
                share(sent, received)
            }
        }
        val time = now()
        val stale = open.entries.filter { it.value.expires && time - it.value.startedAt > MAX_OPEN_MS }
        stale.forEach { (id, call) ->
            open.remove(id)
            expired += closed(call)
        }
    }

    /** Equal shares; the first open call takes what doesn't divide. */
    private fun share(sent: Long, received: Long) {
        val calls = open.values.toList()
        val n = calls.size
        calls.forEachIndexed { index, call ->
            call.sent += sent / n + if (index == 0) sent % n else 0
            call.received += received / n + if (index == 0) received % n else 0
        }
    }

    companion object {
        /** An HTTP call still open after this long is closed with its share so far. */
        const val MAX_OPEN_MS = 10 * 60_000L
    }
}
