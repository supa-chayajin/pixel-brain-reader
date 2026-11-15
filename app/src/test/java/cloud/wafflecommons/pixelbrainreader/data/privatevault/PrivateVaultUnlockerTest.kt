package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The unlock the journal and a meeting's prep share (12.19): verified first, then the session opens. */
class PrivateVaultUnlockerTest {

    @get:Rule val folder = TemporaryFolder()

    private val crypto = CryptoManager()
    private val preferences: UserPreferencesRepository = mockk { every { privateAutoLockMinutes } returns flowOf(5) }
    private val scope = TestScope()
    private val session = PrivateVaultSession(crypto, preferences, scope)
    private val binding: VaultPasswordBinding = mockk(relaxed = true)
    private val followUps: PrivateVaultFollowUps = mockk(relaxed = true)

    private fun unlocker(): PrivateVaultUnlocker {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val notes = PrivateNoteRepository(context, crypto, mockk(relaxed = true), session)
        return PrivateVaultUnlocker(notes, session, binding, followUps, scope)
    }

    private suspend fun note(password: String) {
        val file = File(folder.root, "vault/99_Private/2026-11-15.md.enc")
        file.parentFile!!.mkdirs()
        file.writeBytes(crypto.encrypt("# A note", password.toCharArray()))
    }

    @Test
    fun `the right password opens the session and confirms the binding`() = runTest {
        note("the real one")
        val typed = "the real one".toCharArray()

        assertTrue(unlocker().unlock(typed, viaBiometric = false))

        assertTrue(session.isOpen())
        verify { binding.confirmUnlock(any(), false) }
        // The caller's own copy is left to the caller.
        assertTrue(String(typed) == "the real one")
    }

    @Test
    fun `a wrong password opens nothing`() = runTest {
        note("the real one")

        assertFalse(unlocker().unlock("a typo".toCharArray(), viaBiometric = false))

        assertFalse(session.isOpen())
        verify(exactly = 0) { binding.confirmUnlock(any(), any()) }
    }
}
