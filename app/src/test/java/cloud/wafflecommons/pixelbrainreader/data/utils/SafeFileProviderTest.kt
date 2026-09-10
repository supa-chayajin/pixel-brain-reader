package cloud.wafflecommons.pixelbrainreader.data.utils

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Path-jail + atomic-write behaviour of [SafeFileProvider] (P2.7 + security). */
class SafeFileProviderTest {

    private lateinit var filesDir: File
    private lateinit var provider: SafeFileProvider

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("sfp-test").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        provider = SafeFileProvider(context)
    }

    @Test
    fun `getSafeFile resolves inside the vault jail`() {
        val f = provider.getSafeFile("10_Journal/2026-07-17.md")
        val vaultRoot = File(filesDir, "vault").canonicalPath
        assertTrue(f.canonicalPath.startsWith(vaultRoot))
    }

    @Test
    fun `getSafeFile blocks parent-traversal escape`() {
        assertThrows(SecurityException::class.java) {
            provider.getSafeFile("../../../etc/passwd")
        }
    }

    @Test
    fun `getSafeFile blocks a deep traversal that climbs out`() {
        assertThrows(SecurityException::class.java) {
            provider.getSafeFile("a/b/../../../outside.txt")
        }
    }

    @Test
    fun `getSafeFile blocks an escape into a sibling directory sharing the vault name prefix`() {
        // ".../vault_backup" starts with ".../vault" — a bare prefix check used to let this through.
        assertThrows(SecurityException::class.java) {
            provider.getSafeFile("../vault_backup/stolen.md")
        }
    }

    @Test
    fun `getSafeFile accepts the vault root itself`() {
        val root = provider.getSafeFile("")
        assertEquals(File(filesDir, "vault").canonicalPath, root.canonicalPath)
    }

    @Test
    fun `atomicWrite of bytes round-trips binary content`() {
        val bytes = byteArrayOf(0, 1, 2, -1, -128, 127)
        provider.atomicWrite("99_Private/secret.md.enc", bytes)
        assertTrue(bytes.contentEquals(provider.getSafeFile("99_Private/secret.md.enc").readBytes()))
    }

    @Test
    fun `atomicWrite creates then atomically overwrites`() {
        provider.atomicWrite("notes/hello.md", "first")
        assertEquals("first", provider.getSafeFile("notes/hello.md").readText())

        provider.atomicWrite("notes/hello.md", "second")
        assertEquals("second", provider.getSafeFile("notes/hello.md").readText())

        // No stray temp file left behind.
        val tmp = File(provider.getSafeFile("notes/hello.md").parentFile, "hello.md.tmp")
        assertTrue(!tmp.exists())
    }

    @Test
    fun `atomicWrite stages outside the vault and leaves no temp file anywhere`() {
        provider.atomicWrite("notes/a.md", "one")
        provider.atomicWrite("notes/a.md", "two")

        val vaultFiles = File(filesDir, "vault").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("a.md"), vaultFiles)
        assertTrue(File(filesDir, "vault_staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `concurrent writes of one path always leave one complete version`() {
        val versions = (1..8).map { i -> "version $i\n".repeat(20_000) }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val writers = versions.map { content ->
            Thread {
                try {
                    provider.atomicWrite("notes/busy.md", content)
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join() }

        assertTrue("writes failed: $failures", failures.isEmpty())
        assertTrue(provider.getSafeFile("notes/busy.md").readText() in versions)
    }
}
