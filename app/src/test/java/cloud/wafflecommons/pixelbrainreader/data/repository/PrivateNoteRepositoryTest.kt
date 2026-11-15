package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The unlock's password check (12.19): it samples every encrypted note under `99_Private/`. */
class PrivateNoteRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val crypto = CryptoManager()

    private fun repository(): PrivateNoteRepository {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        return PrivateNoteRepository(context, crypto, mockk<VaultPushScheduler>(relaxed = true), mockk<PrivateVaultSession>(relaxed = true))
    }

    private suspend fun encryptedNote(relativePath: String, password: String, modified: Long) {
        val file = File(folder.root, "vault/99_Private/$relativePath")
        file.parentFile!!.mkdirs()
        file.writeBytes(crypto.encrypt("# A note", password.toCharArray()))
        file.setLastModified(modified)
    }

    @Test
    fun `an empty vault takes any password`() = runTest {
        assertTrue(repository().verifyPassword("anything".toCharArray()))
    }

    @Test
    fun `with an empty journal, a People note still checks the password`() = runTest {
        encryptedNote("People/person-1a2b3c4d.md.enc", "the real one", modified = 2_000_000_000_000)
        val repository = repository()

        assertFalse(repository.verifyPassword("a typo".toCharArray()))
        assertTrue(repository.verifyPassword("the real one".toCharArray()))
    }

    @Test
    fun `the newest notes anywhere under the folder are the ones tried`() = runTest {
        encryptedNote("2026-11-01.md.enc", "the real one", modified = 1_000_000_000_000)
        encryptedNote("People/person-1a2b3c4d.md.enc", "the real one", modified = 2_000_000_000_000)
        val repository = repository()

        assertTrue(repository.verifyPassword("the real one".toCharArray()))
        assertFalse(repository.verifyPassword("a typo".toCharArray()))
    }
}
