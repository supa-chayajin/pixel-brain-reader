package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Time Machine reads a note's history and old versions straight from the on-device repo.
 * Pinned here with REAL JGit: the commit that DELETED a note is part of its history, and reading
 * the note "at" that commit yields null — the sheet must never turn that into an empty note.
 */
class JGitProviderHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var provider: JGitProvider

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath

        val context = mockk<Context> { every { filesDir } returns this@JGitProviderHistoryTest.filesDir }
        val secretManager = mockk<SecretManager> {
            every { getToken() } returns "fake-token"
            every { getProvider() } returns "github"
        }
        provider = JGitProvider(context, secretManager, ConflictResolver(context), CommitGuard.NONE)
    }

    @After
    fun tearDown() {
        SystemReader.setInstance(null)
    }

    @Test
    fun `history lists every commit touching the note, newest first, including its deletion`() = runBlocking {
        seedRemote(NOTE, "v1\n")
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        note().writeText("v2\n")
        assertTrue(provider.commit("edit note").isSuccess)
        note().delete()
        assertTrue(provider.commit("delete note").isSuccess)
        note().writeText("v3\n")
        assertTrue(provider.commit("recreate note").isSuccess)

        val history = provider.getFileCommitHistory(NOTE)

        assertEquals(listOf("recreate note", "delete note", "edit note", "seed"), history.map { it.message })
        val byMessage = history.associateBy { it.message }
        assertEquals("v3\n", provider.getFileContentAtCommit(NOTE, byMessage.getValue("recreate note").commitId))
        assertEquals("v2\n", provider.getFileContentAtCommit(NOTE, byMessage.getValue("edit note").commitId))
        assertEquals("v1\n", provider.getFileContentAtCommit(NOTE, byMessage.getValue("seed").commitId))
        // The note does not exist in the commit that deleted it: null, never "".
        assertNull(provider.getFileContentAtCommit(NOTE, byMessage.getValue("delete note").commitId))
    }

    @Test
    fun `a generic auto-save message is replaced by one naming the staged change`() = runBlocking {
        seedRemote(NOTE, "v1\n")
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        note().writeText("v2\n")
        assertTrue(provider.commit("Auto-save: local changes").isSuccess)
        // Nothing changed since: no new commit.
        assertTrue(provider.commit("Auto-save: local changes").isSuccess)

        val history = provider.getFileCommitHistory(NOTE)
        assertEquals(listOf("projects: update plan", "seed"), history.map { it.message })
    }

    @Test
    fun `an unknown commit id reads as null instead of throwing`() = runBlocking {
        seedRemote(NOTE, "v1\n")
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        assertNull(provider.getFileContentAtCommit(NOTE, "0123456789abcdef0123456789abcdef01234567"))
    }

    private fun note(): File = File(File(filesDir, "vault"), NOTE)

    /** Creates the bare remote seeded with one commit on `main` holding [path] = [content]. */
    private fun seedRemote(path: String, content: String) {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        val seedDir = temp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDir).call().use { git ->
            File(seedDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed").setAuthor("Seed", "seed@test.local").setSign(false).call()
            git.push().setRemote(remoteUri).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    private companion object {
        const val NOTE = "20_Projects/plan.md"
    }
}
