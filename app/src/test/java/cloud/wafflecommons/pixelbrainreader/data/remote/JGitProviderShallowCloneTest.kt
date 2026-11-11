package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
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
import java.io.File
import java.time.Instant

/**
 * The first clone takes the newest commit only and the history follows in steps (2026-11-11: three
 * logins in a row died on a dropped connection, each starting the whole history over). Real JGit
 * against temporary repositories: the clone, the steps, and a pull and a push on a shallow clone.
 */
class JGitProviderShallowCloneTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var context: Context
    private lateinit var provider: JGitProvider

    private val vaultDir: File get() = File(filesDir, "vault")

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath
        context = mockk { every { filesDir } returns this@JGitProviderShallowCloneTest.filesDir }
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
    fun `the first clone takes the newest commit and every note as it is now`() = runBlocking {
        seed(commits = 30)

        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        assertEquals("version 30\n", File(vaultDir, "notes/plan.md").readText())
        assertEquals("note 7\n", File(vaultDir, "notes/n7.md").readText())
        assertEquals(1, localCommits())
        assertTrue(isShallow())
    }

    @Test
    fun `the history arrives in steps, each keeping what the last brought, until complete`() = runBlocking {
        seed(commits = 30)
        provider.setupRepository(remoteUri, "main")

        val counts = mutableListOf<Int>()
        var progress: JGitProvider.HistoryProgress
        do {
            progress = provider.deepenHistory(step = 10).getOrThrow()
            counts += progress.commits
        } while (!progress.complete)

        assertEquals(31, localCommits()) // the seed's first commit + 30
        assertFalse(isShallow())
        // Each step brings more (a server may send more than asked: what is here counts), never less.
        assertTrue("more than one step, never shallower: $counts", counts.size >= 2 && counts.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(11, counts.first())
        assertEquals(31, counts.last())
        assertTrue(provider.deepenHistory().getOrThrow().complete) // nothing left: a no-op
    }

    @Test
    fun `a shallow clone pulls, rebases and pushes like a full one`() = runBlocking {
        seed(commits = 5)
        provider.setupRepository(remoteUri, "main")
        pushFromAnotherDevice("notes/phone.md", "from the phone\n")

        File(vaultDir, "notes/local.md").writeText("written here\n")
        assertTrue(provider.commit("local edit").isSuccess)
        assertTrue(provider.pull() is SyncResult.Success)
        assertTrue(provider.push().isSuccess)

        Git.open(remoteDir).use { remote ->
            val messages = remote.log().setMaxCount(2).call().map { it.shortMessage }
            assertEquals(listOf("local edit", "phone edit notes/phone.md"), messages)
        }
        assertEquals("from the phone\n", File(vaultDir, "notes/phone.md").readText())
    }

    @Test
    fun `file ages are unknown on a shallow history and right once it is complete`() = runBlocking {
        seed(commits = 4)
        provider.setupRepository(remoteUri, "main")
        val ages = GitFileAges(context)

        // Only the newest commit is here: no note may pass for "changed" by it.
        val shallow = ages.history()
        assertEquals(null, shallow.lastChanged["notes/n0.md"])
        assertEquals(null, shallow.firstSeen["notes/plan.md"])

        while (!provider.deepenHistory().getOrThrow().complete) Unit
        val complete = ages.history()
        assertEquals(timeOf(1), complete.lastChanged["notes/n0.md"])
        assertEquals(timeOf(0), complete.firstSeen["notes/plan.md"])
        assertEquals(timeOf(4), complete.lastChanged["notes/plan.md"])
        assertEquals(5, complete.changes["notes/plan.md"])
    }

    @Test
    fun `activity and a note's revisions skip the shallow clone's oldest commit`() = runBlocking {
        seed(commits = 3)
        provider.setupRepository(remoteUri, "main")

        assertEquals(emptyList<CommitSummary>(), provider.commitsSince(0L))
        assertEquals(emptyList<GitCommitDto>(), provider.getFileCommitHistory("notes/plan.md"))

        while (!provider.deepenHistory().getOrThrow().complete) Unit
        assertEquals(4, provider.getFileCommitHistory("notes/plan.md").size)
    }

    @Test
    fun `a boundary announcing history that never came is repaired from what is here`() = runBlocking {
        seed(commits = 30)
        provider.setupRepository(remoteUri, "main")
        provider.deepenHistory(step = 10).getOrThrow() // the newest 11 commits are here
        // What a dropped history step left on the emulator (JGit writes the server's boundary
        // before the pack arrives): a boundary 10 commits below what came, the real edge unlisted.
        val neverCame = Git.open(remoteDir).use { remote -> remote.log().call().toList()[20].id }
        Git.open(vaultDir).use { it.repository.objectDatabase.setShallowCommits(setOf(neverCame)) }
        assertTrue("every walk fails on the missing parent", runCatching { localCommits() }.isFailure)

        assertTrue(provider.pull() is SyncResult.Success) // a sync repairs it first
        assertEquals(11, localCommits())
        while (!provider.deepenHistory(step = 10).getOrThrow().complete) Unit
        assertEquals(31, localCommits())
        assertFalse(isShallow())
    }

    @Test
    fun `a repository that doesn't exist fails at once`() = runBlocking {
        val result = provider.setupRepository("file://" + File(temp.root, "missing.git").absolutePath, "main")

        assertTrue(result.isFailure)
        assertFalse(File(vaultDir, ".git").exists())
    }

    @Test
    fun `only a dropped or timed-out connection is worth another attempt`() {
        assertTrue(isTransientNetworkError(TransportException("https://github.com/o/r.git: Connection reset", java.net.SocketException("Connection reset"))))
        assertTrue(isTransientNetworkError(TransportException("read failed", java.net.SocketTimeoutException("Read timed out"))))
        assertTrue(isTransientNetworkError(java.net.UnknownHostException("github.com")))
        assertTrue(isTransientNetworkError(TransportException("https://github.com/o/r.git: Premature EOF")))
        assertFalse(isTransientNetworkError(TransportException("https://github.com/o/r.git: not authorized")))
        assertFalse(isTransientNetworkError(TransportException("https://github.com/o/r.git: Authentication is required but no CredentialsProvider has been registered")))
        assertFalse(isTransientNetworkError(TransportException("https://github.com/o/r.git: repository not found")))
        assertFalse(isTransientNetworkError(IllegalStateException("a bug")))
    }

    // --- fixtures ---------------------------------------------------------------------

    /** Author time of the seed's commit [index] (0 = the first). */
    private fun timeOf(index: Int): Long = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(3_600L * index).toEpochMilli()

    /**
     * A bare "GitHub" whose history is the first commit (plan v0 + n0) then [commits] more, each
     * rewriting the plan and adding a note; n0 changes once more in commit 1.
     */
    private fun seed(commits: Int) {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        val seedDir = temp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDir).call().use { git ->
            for (i in 0..commits) {
                File(seedDir, "notes/plan.md").apply { parentFile?.mkdirs() }.writeText("version $i\n")
                File(seedDir, "notes/n$i.md").writeText("note $i\n")
                if (i == 1) File(seedDir, "notes/n0.md").writeText("note 0, edited\n")
                git.add().addFilepattern(".").call()
                val who = PersonIdent("Seed", "seed@test.local", Instant.ofEpochMilli(timeOf(i)), java.time.ZoneOffset.UTC)
                git.commit().setMessage("commit $i").setAuthor(who).setCommitter(who).setSign(false).call()
            }
            git.push().setRemote(remoteUri).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    /** The phone (another clone) pushes [content] at [path]. */
    private fun pushFromAnotherDevice(path: String, content: String) {
        val phoneDir = temp.newFolder()
        Git.cloneRepository().setURI(remoteUri).setDirectory(phoneDir).setBranch("main").call().use { git ->
            File(phoneDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("phone edit $path").setAuthor("Phone", "phone@test.local").setSign(false).call()
            git.push().call()
        }
    }

    private fun localCommits(): Int = Git.open(vaultDir).use { git ->
        RevWalk(git.repository).use { walk ->
            walk.markStart(walk.parseCommit(git.repository.resolve("HEAD")))
            walk.count()
        }
    }

    private fun isShallow(): Boolean = Git.open(vaultDir).use { it.repository.objectDatabase.shallowCommits.isNotEmpty() }
}
