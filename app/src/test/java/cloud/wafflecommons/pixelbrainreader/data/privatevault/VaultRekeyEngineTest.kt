package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A change of the private journal's password (12.18, V1), killed at every step and resumed as the
 * next start would: the gate's "kill-mid-way" test. Before the swap a kill leaves the vault exactly
 * as it was; after the manifest the swap always finishes, every note on the new password.
 */
class VaultRekeyEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Killed(point: String) : RuntimeException(point)

    // One instance for the whole class: its derived keys are cached, so the 210k-round PBKDF2 runs once per password.
    private val crypto = CryptoManager()
    private val old = "the current journal password".toCharArray()
    private val new = "a brand new journal password".toCharArray()

    private val notes = mapOf(
        "99_Private/Journal.md.enc" to "Le journal du jour.",
        "99_Private/2026-11-14.md.enc" to "Une autre entrée : été, œuvre, 日記.",
        "99_Private/Mission log/2026-11.md.enc" to "- 2026-11-14 10:00 — Une décision [type:: décision]",
        "99_Private/Mission log/Décisions/ADR 2026-11-14 Choix.md.enc" to "# ADR\n\nLe contexte."
    )

    private val vault get() = File(temp.root, "vault")
    private val work get() = RekeyFiles.workDir(temp.root)

    private suspend fun freshVault() {
        vault.deleteRecursively()
        work.deleteRecursively()
        notes.forEach { (path, text) ->
            File(vault, path).apply { parentFile?.mkdirs() }.writeBytes(crypto.encrypt(text, old))
        }
        File(vault, "10_Journal/2026-11-14.md").apply { parentFile?.mkdirs() }.writeText("A plain note.")
        File(vault, ".git/stray.md.enc").apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun engine(killAt: String? = null) =
        VaultRekeyEngine(vault, work, crypto) { point -> if (point == killAt) throw Killed(point) }

    /** Every file of the vault, as bytes: what "untouched" means. */
    private fun snapshot(): Map<String, List<Byte>> = vault.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(vault).invariantSeparatorsPath to it.readBytes().toList() }

    private suspend fun opensWith(path: String, password: CharArray): Boolean = try {
        crypto.decrypt(File(vault, path).readBytes(), password) == notes.getValue(path)
    } catch (e: Exception) {
        false
    }

    private suspend fun assertAllOn(password: CharArray) {
        notes.keys.forEach { path -> assertTrue("$path should open with the expected password", opensWith(path, password)) }
    }

    @Test
    fun `every encrypted note follows the new password and nothing else moves`() = runTest {
        freshVault()
        assertEquals(VaultRekeyEngine.Outcome.Done(notes.size), engine().change(old, new))

        assertAllOn(new)
        notes.keys.forEach { assertFalse("the old password no longer opens $it", opensWith(it, old)) }
        assertEquals("A plain note.", File(vault, "10_Journal/2026-11-14.md").readText())
        assertEquals(listOf<Byte>(1, 2, 3), File(vault, ".git/stray.md.enc").readBytes().toList())
        assertFalse(work.exists())
        assertFalse(RekeyFiles.swapPending(temp.root))
    }

    @Test
    fun `a note the current password doesn't open stops it before anything is written`() = runTest {
        freshVault()
        File(vault, "99_Private/Other.md.enc").writeBytes(crypto.encrypt("someone else's", "another password".toCharArray()))
        val before = snapshot()

        assertEquals(
            VaultRekeyEngine.Outcome.Unreadable(listOf("99_Private/Other.md.enc")),
            engine().change(old, new)
        )
        assertEquals(before, snapshot())
        assertFalse(work.exists())
    }

    @Test
    fun `killed while staging, the next start leaves the vault exactly as it was`() = runTest {
        for (index in notes.keys.indices) {
            freshVault()
            val before = snapshot()
            try {
                engine(killAt = "staged:$index").change(old, new)
                fail("expected the kill at staged:$index")
            } catch (e: Killed) {
                // the process dies here
            }
            assertFalse("nothing to refuse before the manifest", RekeyFiles.swapPending(temp.root))
            assertEquals(before, snapshot())

            assertEquals(VaultRekeyEngine.Resumed.ROLLED_BACK, engine().resume())
            assertEquals(before, snapshot())
            assertAllOn(old)
            assertFalse(work.exists())
        }
    }

    @Test
    fun `killed once the swap is decided, the next start finishes it`() = runTest {
        val points = listOf("manifest") + notes.keys.indices.map { "swapped:$it" }
        for (point in points) {
            freshVault()
            try {
                engine(killAt = point).change(old, new)
                fail("expected the kill at $point")
            } catch (e: Killed) {
                // the process dies here
            }
            assertTrue("$point: git must refuse to commit a half-swapped vault", RekeyFiles.swapPending(temp.root))
            // Until the swap ends, each note opens with one of the two passwords.
            notes.keys.forEach { path -> assertTrue("$point: $path", opensWith(path, old) || opensWith(path, new)) }

            assertEquals(VaultRekeyEngine.Resumed.FINISHED, engine().resume())
            assertAllOn(new)
            assertFalse(work.exists())
            assertFalse(RekeyFiles.swapPending(temp.root))
        }
    }

    @Test
    fun `a new change waits for a half-done swap to be finished`() = runTest {
        freshVault()
        try {
            engine(killAt = "swapped:0").change(old, new)
        } catch (e: Killed) {
            // the process dies here
        }
        try {
            engine().change(new, "yet another password".toCharArray())
            fail("a change over a half-done swap must refuse")
        } catch (e: IllegalStateException) {
            // the orchestrator resumes first
        }
        assertEquals(VaultRekeyEngine.Resumed.FINISHED, engine().resume())
        assertAllOn(new)
    }

    @Test
    fun `a note changed between its check and the swap stops it clean`() = runTest {
        freshVault()
        val last = notes.size - 1
        val changed = VaultRekeyEngine(vault, work, crypto) { point ->
            if (point == "staged:$last") File(vault, "99_Private/Journal.md.enc").writeBytes(byteArrayOf(9, 9, 9))
        }
        assertEquals(VaultRekeyEngine.Outcome.Moved, changed.change(old, new))
        assertEquals(listOf<Byte>(9, 9, 9), File(vault, "99_Private/Journal.md.enc").readBytes().toList())
        assertTrue(opensWith("99_Private/2026-11-14.md.enc", old))
        assertFalse(work.exists())
    }

    @Test
    fun `nothing to resume when no change was under way`() {
        assertEquals(VaultRekeyEngine.Resumed.NOTHING, engine().resume())
    }
}
