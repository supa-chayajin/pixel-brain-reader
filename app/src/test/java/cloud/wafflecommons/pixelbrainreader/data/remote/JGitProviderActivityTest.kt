package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncDigest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** The activity helpers on a real repository: HEAD, what changed between two commits, recent commits. */
class JGitProviderActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var vault: File
    private lateinit var provider: JGitProvider

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        val filesDir = temp.newFolder("filesDir")
        vault = File(filesDir, "vault").apply { mkdirs() }
        Git.init().setInitialBranch("main").setDirectory(vault).call().close()
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        provider = JGitProvider(context, mockk<SecretManager>(relaxed = true), ConflictResolver(context), CommitGuard.NONE)
    }

    @After
    fun tearDown() {
        SystemReader.setInstance(null)
    }

    private fun commitAt(epochSeconds: Long, message: String, edit: (File) -> Unit): String {
        edit(vault)
        Git.open(vault).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            val who = PersonIdent("Tester", "t@test.local", Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
            return git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call().name
        }
    }

    @Test
    fun `changes between two commits feed the digest, notes only`() = runBlocking {
        assertNull(provider.headId())
        val first = commitAt(1_000, "seed") { File(it, "a.md").writeText("a"); File(it, "b.md").writeText("b") }
        val second = commitAt(2_000, "work") {
            File(it, "a.md").writeText("a2")
            File(it, "b.md").delete()
            File(it, "20_Projects").mkdirs()
            File(it, "20_Projects/c.md").writeText("c")
            File(it, "data.json").writeText("{}")
        }
        assertEquals(second, provider.headId())

        val digest = SyncDigest.of(provider.changesBetween(first, second), 0)
        assertEquals(listOf("20_Projects/c.md"), digest.added)
        assertEquals(listOf("a.md"), digest.modified)
        assertEquals(listOf("b.md"), digest.deleted)
        assertEquals(3, digest.total)
        assertEquals(listOf("Vault" to 2, "20_Projects" to 1), digest.folders())
    }

    @Test
    fun `recent commits stop at the window and list their paths`() = runBlocking {
        commitAt(1_000, "old") { File(it, "old.md").writeText("x") }
        commitAt(5_000, "recent") { File(it, "new.md").writeText("y"); File(it, "old.md").writeText("x2") }

        val commits = provider.commitsSince(sinceMillis = 3_000_000)
        assertEquals(listOf("recent"), commits.map { it.message })
        assertEquals(listOf("new.md", "old.md"), commits.single().paths.sorted())
        assertEquals(5_000_000L, commits.single().timeMillis)
    }
}
