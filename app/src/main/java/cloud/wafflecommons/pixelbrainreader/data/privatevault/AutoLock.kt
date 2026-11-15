package cloud.wafflecommons.pixelbrainreader.data.privatevault

/**
 * The private journal's auto-lock (12.18, V2; owner's D2: 5 minutes by default). A screen showing
 * private content holds the session; when none does, it locks once the delay has passed. Pure: the
 * caller gives the time, `elapsedRealtime`, which keeps counting while the phone sleeps.
 */
class AutoLock {

    private var holders = 0
    private var deadline: Long? = null

    /** A screen showing private content came up. True when the session expired before it did: lock first. */
    fun hold(now: Long): Boolean {
        val expired = expired(now)
        holders++
        deadline = null
        return expired
    }

    /** That screen left. The deadline it starts, or null while another screen still holds the session. */
    fun release(now: Long, delayMs: Long): Long? {
        if (holders > 0) holders--
        if (holders > 0) return null
        return (now + delayMs).also { deadline = it }
    }

    /** An unlock with no screen holding the session starts the deadline at once. */
    fun opened(now: Long, delayMs: Long): Long? =
        if (holders == 0) (now + delayMs).also { deadline = it } else null

    fun expired(now: Long): Boolean = deadline?.let { now >= it } ?: false

    /** The session locked: nothing left to count down. */
    fun locked() {
        deadline = null
    }

    companion object {
        /** The choices the journal's menu offers, in minutes (0 = at once). */
        val CHOICES = listOf(0, 1, 5, 15)
        const val DEFAULT_MINUTES = 5

        /** A stored value the menu doesn't offer (a hand edit, an older choice) falls back to the default. */
        fun minutesOrDefault(minutes: Int): Int = if (minutes in CHOICES) minutes else DEFAULT_MINUTES

        fun label(minutes: Int): String = when (minutes) {
            0 -> "At once"
            1 -> "After 1 minute"
            else -> "After $minutes minutes"
        }
    }
}
