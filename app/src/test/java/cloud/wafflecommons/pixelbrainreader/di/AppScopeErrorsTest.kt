package cloud.wafflecommons.pixelbrainreader.di

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The app-wide scopes' handler (12.19): an exception is logged, an Error still crashes. */
class AppScopeErrorsTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + AppScopeErrors.handler)

    @Test
    fun `an exception is logged without its message, and the scope keeps running`() {
        val scope = scope()

        scope.launch { throw IOException("99_Private/People/someone.md.enc") }
        var ran = false
        scope.launch { ran = true }

        assertTrue(ran)
        assertTrue(scope.isActive)
        verify { Log.e("AppScope", match { it.contains("java.io.IOException") && !it.contains("someone") }) }
    }

    @Test
    fun `a cause is named, its message left out`() {
        val description = AppScopeErrors.describe(IllegalStateException("outer", IOException("inner path")))

        assertTrue(description.contains("java.lang.IllegalStateException"))
        assertTrue(description.contains("Caused by: java.io.IOException"))
        assertTrue(!description.contains("inner path") && !description.contains("outer"))
    }

    @Test
    fun `an error still reaches the crash handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var seen: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen = e }
        try {
            scope().launch { throw OutOfMemoryError("test") }
            assertTrue(seen is OutOfMemoryError)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
