package cloud.wafflecommons.pixelbrainreader.ui.crash

import android.app.Application
import android.content.Intent
import android.os.Process
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

            val intent = Intent(application, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_STACK_TRACE, stackTrace)
            }
            application.startActivity(intent)

            // Kill the process immediately to avoid recovering into a corrupted state
            Process.killProcess(Process.myPid())
            exitProcess(10)

        } catch (e: Exception) {
            // If anything fails in our crash handler, fall back to default
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
