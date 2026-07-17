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
    fun `atomicWrite creates then atomically overwrites`() {
        provider.atomicWrite("notes/hello.md", "first")
        assertEquals("first", provider.getSafeFile("notes/hello.md").readText())

        provider.atomicWrite("notes/hello.md", "second")
        assertEquals("second", provider.getSafeFile("notes/hello.md").readText())

        // No stray temp file left behind.
        val tmp = File(provider.getSafeFile("notes/hello.md").parentFile, "hello.md.tmp")
        assertTrue(!tmp.exists())
    }
}
