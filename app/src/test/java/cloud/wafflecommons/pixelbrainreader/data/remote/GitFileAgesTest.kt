package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
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
import java.time.ZoneOffset

/** Real JGit: file ages come from the history, survive a reload from cache, and update incrementally. */
class GitFileAgesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var vault: File

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        vault = File(filesDir, "vault").apply { mkdirs() }
        Git.init().setInitialBranch("main").setDirectory(vault).call().close()
    }

    @After
    fun tearDown() {
        SystemReader.setInstance(null)
    }

    private fun ages(): GitFileAges {
        val context = mockk<Context> { every { filesDir } returns this@GitFileAgesTest.filesDir }
        return GitFileAges(context)
    }

    private fun commitAt(epochSeconds: Long, message: String, edit: (File) -> Unit) {
        edit(vault)
        Git.open(vault).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call() // stage deletions
            val who = PersonIdent("Tester", "t@test.local", Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
            git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call()
        }
    }

    @Test
    fun `each file gets the time of the last commit that touched it`() = runBlocking {
        commitAt(1_000, "a and b") { File(it, "a.md").writeText("a1"); File(it, "notes/b.md").apply { parentFile?.mkdirs() }.writeText("b1") }
        commitAt(2_000, "edit a") { File(it, "a.md").writeText("a2") }

        val result = ages().lastChanged()

        assertEquals(2_000_000L, result["a.md"])
        assertEquals(1_000_000L, result["notes/b.md"])
    }

    @Test
    fun `new commits are picked up incrementally, including from a fresh instance's cache`() = runBlocking {
        commitAt(1_000, "a and b") { File(it, "a.md").writeText("a1"); File(it, "b.md").writeText("b1") }
        assertEquals(1_000_000L, ages().lastChanged()["b.md"])
        assertTrue(File(filesDir, "git_file_ages.json").exists())

        commitAt(3_000, "edit b, delete a") { File(it, "b.md").writeText("b2"); File(it, "a.md").delete() }
        val result = ages().lastChanged() // new instance: starts from the cached snapshot

        assertEquals(3_000_000L, result["b.md"])
        assertEquals(3_000_000L, result["a.md"]) // its deletion is its last change
    }

    @Test
    fun `first appearance and change counts accumulate, incrementally too`() = runBlocking {
        commitAt(1_000, "a and b") { File(it, "a.md").writeText("a1"); File(it, "b.md").writeText("b1") }
        commitAt(2_000, "edit a") { File(it, "a.md").writeText("a2") }
        val first = ages().history()
        assertEquals(1_000_000L, first.firstSeen["a.md"])
        assertEquals(2, first.changes["a.md"])
        assertEquals(1, first.changes["b.md"])

        commitAt(3_000, "add c, edit a") { File(it, "c.md").writeText("c1"); File(it, "a.md").writeText("a3") }
        val next = ages().history() // new instance: incremental from the cache

        assertEquals(1_000_000L, next.firstSeen["a.md"])
        assertEquals(3_000_000L, next.firstSeen["c.md"])
        assertEquals(3, next.changes["a.md"])
        assertEquals(3_000_000L, next.lastChanged["a.md"])
    }

    @Test
    fun `a cache from before first-seen times is walked again`() = runBlocking {
        commitAt(1_000, "a") { File(it, "a.md").writeText("a1") }
        val head = Git.open(vault).use { it.repository.resolve("HEAD").name }
        File(filesDir, "git_file_ages.json").writeText("""{"head":"$head","lastChanged":{"a.md":1000000}}""")

        assertEquals(1_000_000L, ages().history().firstSeen["a.md"])
    }

    @Test
    fun `a vault that is not a git repository has no ages`() = runBlocking {
        File(vault, ".git").deleteRecursively()
        assertFalse(ages().lastChanged().isNotEmpty())
    }
}
