package cloud.wafflecommons.pixelbrainreader.ui.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.edit
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class GlobalExceptionHandler(
    private val application: Application,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Log the exception (optional, depends on policy, but good for local debugging if logcat is active)
            // Log.e("GlobalExceptionHandler", "Uncaught exception", throwable)

            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))
            val stackTrace = stringWriter.toString()

            // Crash-loop guard: if the app has crashed repeatedly in quick succession, the
            // fault reproduces at startup and re-showing CrashActivity (with its Restart
            // button) would just spin. Hand off to the platform handler instead so the OS
            // shows its own "app keeps stopping" dialog and stops relaunching us.
            if (isCrashLooping()) {
                defaultHandler?.uncaughtException(thread, throwable)
                return
            }

            val intent = Intent(application, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_STACK_TRACE, stackTrace)
            }
            application.startActivity(intent)

            // Kill the process immediately to avoid recovering into a corrupted state
            Process.killProcess(Process.myPid())
            exitProcess(10)

        } catch (e: Exception) {
            // If anything fails in our crash handler (e.g. background-activity-start is
            // blocked because the crash originated off a resumed Activity), fall back to
            // the platform handler.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Records this crash and returns true when [CRASH_LOOP_THRESHOLD] crashes have occurred
     * within [CRASH_LOOP_WINDOW_MS] of each other (across process restarts — the counter
     * lives in SharedPreferences). Uses commit() so the count survives the imminent kill.
     */
    private fun isCrashLooping(): Boolean {
        return try {
            val prefs = application.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val withinWindow = now - prefs.getLong(KEY_LAST_CRASH_AT, 0L) < CRASH_LOOP_WINDOW_MS
            val newCount = if (withinWindow) prefs.getInt(KEY_RECENT_COUNT, 0) + 1 else 1
            prefs.edit(commit = true) {
                putLong(KEY_LAST_CRASH_AT, now)
                putInt(KEY_RECENT_COUNT, newCount)
            }
            withinWindow && newCount >= CRASH_LOOP_THRESHOLD
        } catch (e: Exception) {
            false // Never let the guard itself swallow the crash path.
        }
    }

    companion object {
        private const val CRASH_PREFS = "crash_guard"
        private const val KEY_LAST_CRASH_AT = "last_crash_at"
        private const val KEY_RECENT_COUNT = "recent_count"
        private const val CRASH_LOOP_WINDOW_MS = 10_000L
        private const val CRASH_LOOP_THRESHOLD = 3
    }
}
