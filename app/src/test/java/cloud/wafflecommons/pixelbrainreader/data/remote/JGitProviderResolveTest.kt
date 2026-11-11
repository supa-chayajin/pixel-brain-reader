package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.device.ReadOnlyDeviceException
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChangedException
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChoice
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictFile
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncConflicts
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sync conflict screen's git side (12.15, S1), on REAL JGit repositories: a phone clone (the
 * provider) and a desktop pushing to the same bare remote. A conflict is remembered and pauses the
 * pull; resolving lands ONE commit on top of GitHub's with each file's choice, git merging every
 * other change, the backups gone, pushed.
 */
class JGitProviderResolveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var provider: JGitProvider

    private val vaultDir: File get() = File(filesDir, "vault")

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath
        val context = mockk<Context> { every { filesDir } returns this@JGitProviderResolveTest.filesDir }
        val secretManager = mockk<SecretManager> {
            every { getToken() } returns "fake-token"
            every { getProvider() } returns "github"
        }
        provider = JGitProvider(context, secretManager, ConflictResolver(context), CommitGuard.NONE)
    }

    @After
    fun tearDown() {
        SystemReader.setInstance(null)
        VaultWriteGate.readOnly = false
    }

    /** The phone edits [STORY] (and [OTHER] when [localOther] is set) while the desktop edits [STORY]: a pull conflicts. */
    private suspend fun conflictOnStory(localOther: String? = null, alsoNew: Boolean = false) {
        seedRemote(mapOf(STORY to BASE, OTHER to BASE_Y))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)
        vaultFile(STORY).writeText(LOCAL)
        localOther?.let { vaultFile(OTHER).writeText(it) }
        if (alsoNew) vaultFile(NEW).apply { parentFile?.mkdirs() }.writeText("une note du téléphone\n")
        assertTrue(provider.commit("phone edits").isSuccess)
        pushRemoteEdit(STORY, REMOTE)
        assertTrue(provider.pull() is SyncResult.ResolvedWithConflicts)
    }

    // --- The pause ---

    @Test
    fun `a conflict is remembered, and later pulls pause without a new backup`() = runBlocking {
        val stops = AtomicInteger()
        val listening = launch(Dispatchers.Unconfined) { provider.conflictStops.collect { stops.incrementAndGet() } }
        conflictOnStory()

        val pending = provider.pendingConflict()
        assertNotNull(pending)
        assertEquals(listOf(STORY), pending!!.paths)
        assertEquals(1, pending.backups.size)
        assertEquals(remoteHead().name, pending.remote)

        val again = provider.pull()
        assertTrue("got $again", again is SyncResult.ConflictPending)
        assertEquals(listOf(STORY), (again as SyncResult.ConflictPending).paths)
        assertEquals(1, conflictBackups().size)

        // The stop, then the pause: each opens the conflict screen.
        withTimeout(5_000) { while (stops.get() < 2) delay(10) }
        listening.cancel()
        assertEquals(2, stops.get())
    }

    @Test
    fun `once GitHub's side matches the phone's, nothing conflicts and resolving just resumes`() = runBlocking {
        conflictOnStory()
        pushRemoteEdit(STORY, LOCAL) // the desktop took the phone's version
        Git.open(vaultDir).use { it.fetch().setRemote("origin").call() } // a history step fetched it meanwhile

        assertEquals(emptyList<ConflictFile>(), provider.conflictFiles().getOrThrow())

        assertTrue(provider.resolveConflicts(emptyMap()).getOrThrow())
        assertEquals(LOCAL, vaultFile(STORY).readText())
        assertEquals(remoteHead(), vaultHead())
        assertNull(provider.pendingConflict())
        assertEquals(emptyList<File>(), conflictBackups())
        assertTrue("the next pull runs again", provider.pull() is SyncResult.Success)
    }

    @Test
    fun `the conflict's files carry both versions and git's marked merge`() = runBlocking {
        conflictOnStory()

        val files = provider.conflictFiles().getOrThrow()

        assertEquals(1, files.size)
        val story = files.single()
        assertEquals(STORY, story.path)
        assertEquals(LOCAL, story.mine)
        assertEquals(REMOTE, story.theirs)
        val merged = story.merged!!
        assertTrue(merged, merged.contains("<<<<<<< téléphone"))
        assertTrue(merged.contains(LOCAL.trim()))
        assertTrue(merged.contains(REMOTE.trim()))
        assertTrue(merged.contains(">>>>>>> GitHub"))
        assertFalse(story.isData)
    }

    // --- The choices ---

    @Test
    fun `keeping mine lands one commit on GitHub's, git merging the rest, the backups gone, pushed`() = runBlocking {
        conflictOnStory(alsoNew = true)
        pushRemoteEdit(OTHER, REMOTE_Y) // the desktop also changed a file the phone didn't
        val github = remoteHead()

        val pushed = provider.resolveConflicts(mapOf(STORY to ConflictChoice.Mine)).getOrThrow()

        assertTrue(pushed)
        assertEquals(LOCAL, vaultFile(STORY).readText())
        assertEquals(REMOTE_Y, vaultFile(OTHER).readText())
        assertTrue(vaultFile(NEW).isFile)
        assertEquals(emptyList<File>(), conflictBackups())
        assertNull(provider.pendingConflict())
        Git.open(vaultDir).use { git ->
            assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
            assertTrue(git.status().call().isClean)
            val head = git.repository.parseCommit(git.repository.resolve("HEAD"))
            assertEquals(1, head.parentCount)
            assertEquals(github, head.getParent(0).id)
            assertEquals(head.id, remoteHead())
        }
        assertTrue("the next pull runs again", provider.pull() is SyncResult.Success)
    }

    @Test
    fun `keeping theirs takes GitHub's text, and nothing of the phone's is left to push`() = runBlocking {
        conflictOnStory()
        val github = remoteHead()

        assertTrue(provider.resolveConflicts(mapOf(STORY to ConflictChoice.Theirs)).getOrThrow())

        assertEquals(REMOTE, vaultFile(STORY).readText())
        assertEquals(github, vaultHead())
        assertEquals(github, remoteHead())
        assertNull(provider.pendingConflict())
    }

    @Test
    fun `an edited text is what lands`() = runBlocking {
        conflictOnStory()

        assertTrue(provider.resolveConflicts(mapOf(STORY to ConflictChoice.Edited("fusion faite à la main\n"))).getOrThrow())

        assertEquals("fusion faite à la main\n", vaultFile(STORY).readText())
        assertEquals(vaultHead(), remoteHead())
    }

    @Test
    fun `keeping both leaves GitHub's in place and the phone's beside it`() = runBlocking {
        conflictOnStory()

        assertTrue(provider.resolveConflicts(mapOf(STORY to ConflictChoice.Both)).getOrThrow())

        assertEquals(REMOTE, vaultFile(STORY).readText())
        assertEquals(LOCAL, vaultFile("notes/story (téléphone).md").readText())
        assertEquals(vaultHead(), remoteHead())
    }

    // --- When GitHub moves again ---

    @Test
    fun `a new conflict without a choice aborts and keeps the conflict, the phone's work intact`() = runBlocking {
        conflictOnStory(localOther = LOCAL_Y)
        pushRemoteEdit(OTHER, REMOTE_Y) // after the pull stopped: OTHER now conflicts too

        val result = provider.resolveConflicts(mapOf(STORY to ConflictChoice.Mine))

        val error = result.exceptionOrNull()
        assertTrue("got $result", error is ConflictChangedException)
        assertEquals(listOf(OTHER), (error as ConflictChangedException).paths)
        assertNotNull(provider.pendingConflict())
        assertEquals(LOCAL, vaultFile(STORY).readText())
        assertEquals(LOCAL_Y, vaultFile(OTHER).readText())
        Git.open(vaultDir).use { git -> assertEquals(RepositoryState.SAFE, git.repository.repositoryState) }
    }

    @Test
    fun `a read-only device resolves nothing`() = runBlocking {
        conflictOnStory()
        VaultWriteGate.readOnly = true

        assertTrue(provider.resolveConflicts(mapOf(STORY to ConflictChoice.Mine)).exceptionOrNull() is ReadOnlyDeviceException)
    }

    // --- Pure ---

    @Test
    fun `the remembered conflict reads back, and a copy is named after the phone`() {
        val pending = cloud.wafflecommons.pixelbrainreader.data.sync.PendingConflict("abc123", listOf("a b/c.md", "d.json"), listOf("a b/c_sync_conflict_1.md"))
        assertEquals(pending, SyncConflicts.decode(SyncConflicts.encode(pending)))
        assertNull(SyncConflicts.decode("garbage"))
        assertEquals("notes/story (téléphone).md", SyncConflicts.copyPath("notes/story.md"))
        assertEquals("README (téléphone)", SyncConflicts.copyPath("README"))
        assertEquals("a.b/c (téléphone).json", SyncConflicts.copyPath("a.b/c.json"))
    }

    // --- Helpers (as JGitProviderConflictTest) ---

    private fun seedRemote(files: Map<String, String>) {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        val seedDir = temp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDir).call().use { git ->
            files.forEach { (path, content) -> File(seedDir, path).apply { parentFile?.mkdirs() }.writeText(content) }
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed").setAuthor("Seed", "seed@test.local").setSign(false).call()
            git.push().setRemote(remoteUri).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    private fun pushRemoteEdit(path: String, content: String) {
        val collabDir = temp.newFolder()
        Git.cloneRepository().setURI(remoteUri).setDirectory(collabDir).setBranch("main").call().use { git ->
            File(collabDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("desktop edit $path").setAuthor("Desktop", "desktop@test.local").setSign(false).call()
            git.push().call()
        }
    }

    private fun vaultFile(relativePath: String): File = File(vaultDir, relativePath)

    private fun vaultHead(): ObjectId = Git.open(vaultDir).use { requireNotNull(it.repository.resolve("HEAD")) }

    private fun remoteHead(): ObjectId = Git.open(remoteDir).use { requireNotNull(it.repository.resolve("refs/heads/main")) }

    private fun conflictBackups(): List<File> =
        vaultDir.walkTopDown()
            .filter { it.isFile && !it.path.contains("${File.separator}.git${File.separator}") }
            .filter { it.name.contains("_sync_conflict_") }
            .toList()

    private companion object {
        const val STORY = "notes/story.md"
        const val OTHER = "notes/autre.md"
        const val NEW = "notes/nouvelle.md"
        const val BASE = "ligne de base\n"
        const val BASE_Y = "contenu initial Y\n"
        const val LOCAL = "version du téléphone\n"
        const val LOCAL_Y = "Y vu par le téléphone\n"
        const val REMOTE = "version du bureau\n"
        const val REMOTE_Y = "Y vu par le bureau\n"
    }
}
