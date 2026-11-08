package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.device.ReadOnlyDeviceException
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Layer 4 of the read-only device (documentation/plans/2026-11-08-read-only-device.md): with
 * [VaultWriteGate.readOnly] set, git refuses every write and a pull only fast-forwards. Real JGit
 * against temporary repositories, like JGitProviderConflictTest.
 */
class JGitProviderReadOnlyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var provider: JGitProvider

    private val vaultDir: File
        get() = File(filesDir, "vault")

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath
        val context = mockk<Context> { every { filesDir } returns this@JGitProviderReadOnlyTest.filesDir }
        val secretManager = mockk<SecretManager> {
            every { getToken() } returns "fake-token"
            every { getProvider() } returns "github"
        }
        provider = JGitProvider(context, secretManager, ConflictResolver(context), CommitGuard.NONE)
        VaultWriteGate.readOnly = false
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        SystemReader.setInstance(null)
    }

    @Test
    fun `read-only refuses commit and push - nothing recorded, nothing sent`() = runBlocking {
        cloneSeededVault()
        VaultWriteGate.readOnly = true
        val headBefore = vaultHead()
        val remoteBefore = remoteHead()
        vaultFile(STORY).writeText("a stray write\n")

        assertTrue(provider.commit("must not happen").exceptionOrNull() is ReadOnlyDeviceException)
        assertTrue(provider.push().exceptionOrNull() is ReadOnlyDeviceException)
        assertTrue(provider.commitAndForcePush("must not happen").exceptionOrNull() is ReadOnlyDeviceException)
        assertTrue(provider.addAll().exceptionOrNull() is ReadOnlyDeviceException)
        assertTrue(provider.setRemote("file:///elsewhere").exceptionOrNull() is ReadOnlyDeviceException)

        assertEquals(headBefore, vaultHead())
        assertEquals(remoteBefore, remoteHead())
        Git.open(vaultDir).use { git ->
            val status = git.status().call()
            assertTrue("nothing staged", status.added.isEmpty() && status.changed.isEmpty())
            assertEquals(remoteUri, git.repository.config.getString("remote", "origin", "url"))
        }
    }

    @Test
    fun `read-only pull fast-forwards to what GitHub has`() = runBlocking {
        cloneSeededVault()
        VaultWriteGate.readOnly = true
        pushRemoteEdit(STORY, REMOTE)

        val result = provider.pull()

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals(REMOTE, vaultFile(STORY).readText())
        assertEquals(remoteHead(), vaultHead())
        assertTrue(provider.localChanges().isEmpty)
    }

    @Test
    fun `read-only pull never merges a diverged branch`() = runBlocking {
        cloneSeededVault()
        // A commit this copy should never have made (an old write-mode clone, say)…
        vaultFile(OTHER).writeText("local\n")
        assertTrue(provider.commit("local commit").isSuccess)
        val localHead = vaultHead()
        VaultWriteGate.readOnly = true
        // …and GitHub moved on without it.
        pushRemoteEdit(STORY, REMOTE)
        val remoteBefore = remoteHead()

        val result = provider.pull()

        assertTrue("got $result", result is SyncResult.Error && result.exception is ReadOnlyDeviceException)
        assertEquals("no merge commit, no rebase", localHead, vaultHead())
        assertEquals(remoteBefore, remoteHead())
        assertEquals(BASE, vaultFile(STORY).readText())
    }

    @Test
    fun `local changes list stray files and unpushed commits`() = runBlocking {
        cloneSeededVault()
        vaultFile(OTHER).writeText("committed, never pushed\n")
        assertTrue(provider.commit("ahead").isSuccess)
        vaultFile(STORY).writeText("modified\n")
        vaultFile("notes/new.md").writeText("untracked\n")

        val changes = provider.localChanges()

        assertEquals(listOf("notes/new.md", STORY), changes.files)
        assertEquals(1, changes.unpushedCommits)
        assertFalse(changes.isEmpty)
    }

    @Test
    fun `reset to GitHub discards a read-only copy's stray changes`() = runBlocking {
        cloneSeededVault()
        vaultFile(OTHER).writeText("stray commit\n")
        assertTrue(provider.commit("stray").isSuccess)
        VaultWriteGate.readOnly = true
        vaultFile(STORY).writeText("stray edit\n")
        vaultFile("stray/new.md").apply { parentFile?.mkdirs() }.writeText("stray file\n")
        pushRemoteEdit(STORY, REMOTE)

        assertTrue(provider.resetToRemote().isSuccess)

        assertEquals(REMOTE, vaultFile(STORY).readText())
        assertFalse(vaultFile(OTHER).exists())
        assertFalse(vaultFile("stray").exists())
        assertEquals(remoteHead(), vaultHead())
        assertTrue(provider.localChanges().isEmpty)
    }

    @Test
    fun `reset to GitHub is refused on a device that writes`() = runBlocking {
        cloneSeededVault()
        vaultFile(STORY).writeText("real work\n")

        assertTrue(provider.resetToRemote().isFailure)
        assertEquals("real work\n", vaultFile(STORY).readText())
    }

    @Test
    fun `a read-only device only clones - it never starts a local repository`() = runBlocking {
        VaultWriteGate.readOnly = true

        assertTrue(provider.setupRepository(null, "main").exceptionOrNull() is ReadOnlyDeviceException)
        assertFalse(provider.isReady())
        assertTrue("a read-only device still clones", provider.setupRepository(seed(), "main").isSuccess)
        assertEquals(BASE, vaultFile(STORY).readText())
    }

    @Test
    fun `deleting the local clone leaves nothing to reopen`() = runBlocking {
        cloneSeededVault()
        assertEquals(remoteUri, provider.originUrl())

        assertTrue(provider.deleteLocalClone())

        assertFalse(provider.isReady())
        assertFalse(vaultDir.exists())
        assertFalse(File(filesDir, "vault_discarded").exists())
        assertEquals(null, provider.originUrl())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun cloneSeededVault() {
        assertTrue(provider.setupRepository(seed(), "main").isSuccess)
    }

    /** A bare "GitHub" holding [STORY] at [BASE]; returns its URI. */
    private fun seed(): String {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        val seedDir = temp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDir).call().use { git ->
            File(seedDir, STORY).apply { parentFile?.mkdirs() }.writeText(BASE)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed").setAuthor("Seed", "seed@test.local").setSign(false).call()
            git.push().setRemote(remoteUri).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        return remoteUri
    }

    /** The phone (another clone) pushes an edit of [path]. */
    private fun pushRemoteEdit(path: String, content: String) {
        val phoneDir = temp.newFolder()
        Git.cloneRepository().setURI(remoteUri).setDirectory(phoneDir).setBranch("main").call().use { git ->
            File(phoneDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("phone edit $path").setAuthor("Phone", "phone@test.local").setSign(false).call()
            git.push().call()
        }
    }

    private fun vaultFile(relativePath: String): File = File(vaultDir, relativePath)

    private fun vaultHead(): ObjectId = Git.open(vaultDir).use { requireNotNull(it.repository.resolve("HEAD")) }

    private fun remoteHead(): ObjectId = Git.open(remoteDir).use { requireNotNull(it.repository.resolve("refs/heads/main")) }

    private companion object {
        const val STORY = "notes/story.md"
        const val OTHER = "notes/other.md"
        const val BASE = "base line\n"
        const val REMOTE = "the phone's version\n"
    }
}
