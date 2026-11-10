package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.work.ErrorTrace.Ecosystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C8: what reads as an error, what it says, and the words worth searching. */
class ErrorTraceTest {

    private fun parse(text: String): ErrorTrace =
        ErrorTrace.parse(text.trimIndent()) ?: throw AssertionError("should read as an error:\n$text")

    @Test
    fun `a Kotlin or Java stack trace`() {
        val trace = parse(
            """
            java.lang.IllegalStateException: Vault not cloned at /data/user/0/app/files/vault
            	at cloud.example.data.SyncOrchestrator.run(SyncOrchestrator.kt:142)
            	at cloud.example.ui.MainViewModel.sync(MainViewModel.kt:88)
            Caused by: java.io.IOException: No space left on device
            	at java.io.FileOutputStream.write(FileOutputStream.java:12)
            """
        )
        assertEquals(Ecosystem.JVM, trace.ecosystem)
        assertEquals("IllegalStateException", trace.type)
        assertEquals(3, trace.frames.size)
        assertEquals("java.lang.IllegalStateException: Vault not cloned at vault", trace.key)
    }

    @Test
    fun `a Python traceback reports its error last`() {
        val trace = parse(
            """
            Traceback (most recent call last):
              File "/home/me/tool/cli.py", line 3, in <module>
                import click
            ModuleNotFoundError: No module named 'click'
            """
        )
        assertEquals(Ecosystem.PYTHON, trace.ecosystem)
        assertEquals("ModuleNotFoundError", trace.type)
        assertEquals("ModuleNotFoundError click", trace.queries().first())
    }

    @Test
    fun `a PHP fatal error with its stack`() {
        val trace = parse(
            """
            PHP Fatal error:  Uncaught TypeError: Envelope::${'$'}attempts::set(): Argument #1 (${'$'}value) must be of type int, string given in /var/www/src/Queue/Envelope.php:42
            Stack trace:
            #0 /var/www/src/Queue/Worker.php(88): Envelope->setAttempts('3')
            #1 {main}
            """
        )
        assertEquals(Ecosystem.PHP, trace.ecosystem)
        assertEquals("TypeError", trace.type)
        assertEquals("TypeError Envelope attempts", trace.queries().first())
    }

    @Test
    fun `a JavaScript error with Node frames`() {
        val trace = parse(
            """
            TypeError: Cannot read properties of undefined (reading 'map')
                at renderList (/app/src/components/List.js:14:22)
                at processChild (/app/node_modules/react-dom/server.js:3043:14)
            """
        )
        assertEquals(Ecosystem.JAVASCRIPT, trace.ecosystem)
        assertEquals("TypeError map properties", trace.queries().first())
    }

    @Test
    fun `a dotnet unhandled exception`() {
        val trace = parse(
            """
            Unhandled exception. System.InvalidOperationException: Sequence contains no elements
               at System.Linq.ThrowHelper.ThrowNoElementsException()
               at Shop.Orders.OrderService.Latest(Int32 customerId) in /src/Orders/OrderService.cs:line 57
            """
        )
        assertEquals(Ecosystem.DOTNET, trace.ecosystem)
        assertEquals("InvalidOperationException", trace.type)
    }

    @Test
    fun `a Go panic`() {
        val trace = parse(
            """
            panic: runtime error: index out of range [5] with length 3

            goroutine 1 [running]:
            main.main()
            	/home/me/app/main.go:12 +0x1d
            """
        )
        assertEquals(Ecosystem.GO, trace.ecosystem)
        assertEquals("panic", trace.type)
        assertEquals("runtime error: index out of range [5] with length 3", trace.message)
    }

    @Test
    fun `a bare error line with a code`() {
        val trace = parse("SQLSTATE[23000]: Integrity constraint violation: 1062 Duplicate entry 'x' for key 'PRIMARY'")
        assertEquals(Ecosystem.MESSAGE, trace.ecosystem)
        assertEquals(listOf("SQLSTATE[23000]"), trace.codes)
        assertTrue(trace.queries().contains("SQLSTATE[23000]"))
    }

    @Test
    fun `prose is not an error, even when it names one`() {
        assertNull(ErrorTrace.parse("Je suis allé au marché ce matin.\nIl faisait beau.\nNous avons acheté des pommes.\nPuis nous sommes rentrés."))
        assertNull(ErrorTrace.parse("Notes on handling a ValueError in our parser.\nWe discussed retries.\nThen lunch.\nThe end."))
        assertNull(ErrorTrace.parse("   \n  "))
    }

    @Test
    fun `two logs of one failure give one key`() {
        val a = ErrorTrace.keyOf("IllegalStateException: Job 8f9e2c1a-1b2c-4d5e-8f90-123456789abc failed after 3021 ms at 2026-11-10T21:00:01Z in /srv/app/jobs/Runner.kt")
        val b = ErrorTrace.keyOf("IllegalStateException: Job 0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d failed after 2875 ms at 2026-11-09T08:12:45Z in /home/ci/app/jobs/Runner.kt")
        assertEquals(a, b)
    }
}
