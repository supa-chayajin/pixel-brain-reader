package cloud.wafflecommons.pixelbrainreader.data.focus

import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyActivity

/**
 * One focus block (D9, owner 2026-11-11), pure: its start and length, kept on the phone so it
 * survives the app being closed. The countdown is the system's; the progress bar steps every
 * [STEP_MS] through WorkManager.
 */
data class FocusSession(val startMillis: Long, val minutes: Int) {

    val lengthMillis: Long get() = minutes * 60_000L
    val endMillis: Long get() = startMillis + lengthMillis

    fun isOver(now: Long): Boolean = now >= endMillis

    /** 0..100, from the start to the end. */
    fun progressPercent(now: Long): Int = ((now - startMillis).coerceIn(0L, lengthMillis) * 100 / lengthMillis).toInt()

    /** The next step after [now]: a multiple of [STEP_MS] from the start, the end at the latest. */
    fun nextStep(now: Long): Long {
        val done = ((now - startMillis).coerceAtLeast(0L) / STEP_MS) + 1
        return minOf(startMillis + done * STEP_MS, endMillis)
    }

    fun encode(): String = "$startMillis|$minutes"

    companion object {
        val DURATIONS = listOf(25, 50, 90)
        const val DEFAULT_MINUTES = 50
        const val STEP_MS = 5 * 60_000L

        /** Edits saved just before the end reach git within the push scheduler's 15 s, plus margin. */
        const val GRACE_MS = 2 * 60_000L

        fun decode(raw: String?): FocusSession? {
            val (start, minutes) = raw?.split('|')?.takeIf { it.size == 2 } ?: return null
            val session = FocusSession(start.toLongOrNull() ?: return null, minutes.toIntOrNull() ?: return null)
            return session.takeIf { it.minutes in 1..600 && it.startMillis > 0 }
        }

        /** XP for a session that ran to its end: half a point a minute (a task is 20). */
        fun xpFor(minutes: Int): Int = minutes / 2
    }
}

/**
 * What the Live Update shows (pure, so the JVM tests can read it; [FocusPlatform] maps it 1:1 onto
 * the platform builder): an ongoing notification asking for promotion, the system's countdown to
 * [countdownTo], and the bar at [progress].
 */
data class FocusSpec(val title: String, val text: String, val progress: Int, val countdownTo: Long, val ongoing: Boolean, val promoted: Boolean) {
    companion object {
        fun running(session: FocusSession, now: Long, endLabel: String) = FocusSpec(
            title = "Focus · ${session.minutes} min",
            text = "Until $endLabel",
            progress = session.progressPercent(now),
            countdownTo = session.endMillis,
            ongoing = true,
            promoted = true
        )

        /** "Focus done · 3 notes edited". */
        fun doneTitle(notes: Int): String = when (notes) {
            0 -> "Focus done"
            1 -> "Focus done · 1 note edited"
            else -> "Focus done · $notes notes edited"
        }
    }
}

/** The notes of a finished session, for its mission-log fact (D9). */
object FocusNotes {

    const val TAG = "focus"

    /**
     * The notes last changed during [session] (git's author time), with the weekly review's
     * exclusions: never a daily or weekly note, data, a private or People note, a mirror. [keep]
     * drops what the caller knows is gone or generated.
     */
    fun edited(history: FileHistory, session: FocusSession, keep: (String) -> Boolean = { true }): List<String> {
        val until = session.endMillis + FocusSession.GRACE_MS
        return history.lastChanged.entries
            .filter { (path, at) -> at in session.startMillis..until && path.endsWith(".md") && !WeeklyActivity.excluded(path) }
            .sortedByDescending { it.value }
            .map { it.key }
            .filter(keep)
    }

    /** The fact's French text: the session and the notes it touched, as path-style links. */
    fun logText(minutes: Int, notes: List<String>): String {
        val head = "Session de concentration de $minutes min"
        if (notes.isEmpty()) return head
        val shown = notes.take(8).map(WeeklyActivity::link)
        val tail = when (val more = notes.size - shown.size) {
            0 -> ""
            1 -> " et 1 autre"
            else -> " et $more autres"
        }
        return "$head : " + shown.joinToString(", ") + tail
    }
}
