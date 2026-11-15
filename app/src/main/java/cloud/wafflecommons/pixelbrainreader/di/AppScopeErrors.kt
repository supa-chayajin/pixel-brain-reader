package cloud.wafflecommons.pixelbrainreader.di

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The app-wide scopes' last resort (12.19, the owner's call after the pre-release review): an
 * exception escaping one of their launches is logged and the app keeps running. The log holds the
 * exception's type and stack, never its message, which can hold a note's path. An Error (out of
 * memory, a stack overflow) still reaches the crash screen: the app can't run on safely after one.
 */
object AppScopeErrors {

    private const val TAG = "AppScope"
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 12

    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is Exception) {
            Log.e(TAG, "A background task failed: " + describe(throwable))
        } else {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    /** The type and first frames of [throwable] and of its causes, without their messages. */
    internal fun describe(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name)
            current.stackTrace.take(MAX_FRAMES).forEach { append("\n  at ").append(it) }
            val cause = current.cause
            current = if (cause === current) null else cause
            depth++
        }
    }
}
