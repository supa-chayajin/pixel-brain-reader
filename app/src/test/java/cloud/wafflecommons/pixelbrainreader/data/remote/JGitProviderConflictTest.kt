package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression pins for the JGit pull/rebase conflict flow — the last line of defence
 * for a user's local edits (JGitProvider.pull + ConflictResolver).
 *
 * These tests run REAL JGit against temporary directories (org.eclipse.jgit is a plain
 * JVM dependency); only the Android bits (Context.filesDir, SecretManager) are mocked.
 *
 * Contract pinned here (current actual behavior, JGit 6.8.0):
 *  - Conflicting divergence (both sides committed): pull() returns
 *    [SyncResult.ResolvedWithConflicts] (never Success), the rebase is aborted, the
 *    repository is left SAFE with no uncommitted changes, the local commit + content
 *    are fully preserved, and nothing is pushed to the remote. JGit reports
 *    RebaseResult.Status.STOPPED whose conflict list is null, so the provider derives
 *    the conflicted paths from git status WHILE the rebase is stopped, and after the
 *    abort writes a timestamped backup of the restored local content — belt (local
 *    commit) and suspenders (backup file).
 *  - Dirty uncommitted local edit vs a remote commit on the same file: JGit reports
 *    Status.CONFLICTS with the conflicting paths, so pull() returns
 *    ResolvedWithConflicts(1) and ConflictResolver DOES write a timestamped backup of
 *    the dirty local content next to the original; the working tree is left untouched.
 *  - Non-conflicting divergence: pull() rebases the local commit on top of the remote
 *    head, returns [SyncResult.Success], both edits are present, and no conflict
 *    backup files are created.
 */
class JGitProviderConflictTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var provider: JGitProvider
    private var previousSystemReader: SystemReader? = null

    private val vaultDir: File
        get() = File(filesDir, "vault")

    @Before
    fun setUp() {
        // Isolate JGit from the machine's real ~/.gitconfig and /etc gitconfig
        // (init.defaultBranch, commit.gpgsign, lfs filters… must not leak into tests).
        previousSystemReader = SystemReader.getInstance()
        SystemReader.setInstance(
            IsolatedSystemReader(previousSystemReader!!, temp.newFolder("gitconfigs"))
        )

        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath

        val context = mockk<Context> {
            every { filesDir } returns this@JGitProviderConflictTest.filesDir
        }
        val secretManager = mockk<SecretManager> {
            every { getToken() } returns "fake-token"
            every { getProvider() } returns "github"
        }
        provider = JGitProvider(context, secretManager, ConflictResolver(context))
    }

    @After
    fun tearDown() {
        // Restore JGit's default SystemReader for other test classes in the same JVM.
        SystemReader.setInstance(null)
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    fun `conflicting divergence - pull reports conflict, aborts to a clean SAFE repo, preserves local commit, pushes nothing`() {
        runBlocking {
            seedRemote(mapOf(STORY to BASE))
            assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

            // Local committed edit of file X…
            vaultFile(STORY).writeText(LOCAL)
            assertTrue(provider.commit("local edit").isSuccess)
            val localHeadBefore = vaultHead()

            // …vs a DIFFERENT committed edit of the same file already on the remote.
            pushRemoteEdit(STORY, REMOTE)
            val remoteHeadBefore = remoteHead()

            val result = provider.pull()

            // (a) The conflict variant is returned — NOT Success, NOT Error.
            assertTrue(
                "conflicting rebase must surface ResolvedWithConflicts, got $result",
                result is SyncResult.ResolvedWithConflicts
            )

            // (b) STOPPED's getConflicts() is null in JGit 6.8, so the provider derives
            // the conflicted paths from git status while the rebase is stopped and,
            // after the abort restores the clean local state, backs up the LOCAL
            // content next to the original — the recoverable copy the sync-error
            // message promises the user.
            assertEquals(1, (result as SyncResult.ResolvedWithConflicts).backedUpFilesCount)
            val backups = conflictBackups()
            assertEquals(1, backups.size)
            assertEquals(vaultFile(STORY).parentFile, backups.single().parentFile)
            assertEquals(LOCAL, backups.single().readText())

            // (c) The rebase was aborted: repository SAFE, no uncommitted changes
            //     (the backup file is deliberately untracked), local branch restored
            //     to the local commit with the local content.
            Git.open(vaultDir).use { git ->
                assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
                val status = git.status().call()
                assertTrue(
                    "no staged/modified changes may remain after abort",
                    !status.hasUncommittedChanges()
                )
            }
            assertEquals(localHeadBefore, vaultHead())
            assertEquals(LOCAL, vaultFile(STORY).readText())

            // (d) Nothing was pushed into the diverged remote history.
            assertEquals(remoteHeadBefore, remoteHead())
        }
    }

    @Test
    fun `non-conflicting divergence - pull rebases local commit on top, keeps both edits, creates no backups`() {
        runBlocking {
            seedRemote(mapOf(STORY to BASE, OTHER to "contenu initial Y\n"))
            assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

            // Local commits file X; remote independently commits file Y.
            vaultFile(STORY).writeText(LOCAL)
            assertTrue(provider.commit("local edit X").isSuccess)
            pushRemoteEdit(OTHER, REMOTE_Y)
            val remoteHeadBefore = remoteHead()

            val result = provider.pull()

            assertTrue("non-conflicting rebase must succeed, got $result", result is SyncResult.Success)

            // Both changes present in the working tree.
            assertEquals(LOCAL, vaultFile(STORY).readText())
            assertEquals(REMOTE_Y, vaultFile(OTHER).readText())

            // Local commit was REBASED on top of the remote head (no merge commit).
            Git.open(vaultDir).use { git ->
                assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
                assertTrue(git.status().call().isClean)
                val head = git.repository.parseCommit(git.repository.resolve("HEAD"))
                assertEquals(1, head.parentCount)
                assertEquals(remoteHeadBefore, head.getParent(0).id)
            }

            // No conflict backup files were created anywhere in the vault.
            assertEquals(emptyList<File>(), conflictBackups())

            // pull() never pushes.
            assertEquals(remoteHeadBefore, remoteHead())
        }
    }

    @Test
    fun `dirty uncommitted local edit vs remote commit - pull reports conflict, backs up the dirty file, leaves tree untouched`() {
        runBlocking {
            seedRemote(mapOf(STORY to BASE))
            assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)
            val localHeadBefore = vaultHead()

            // Local UNCOMMITTED edit of file X…
            vaultFile(STORY).writeText(LOCAL)

            // …vs a committed remote edit of the same file.
            pushRemoteEdit(STORY, REMOTE)
            val remoteHeadBefore = remoteHead()

            val result = provider.pull()

            // JGit refuses the rebase checkout (Status.CONFLICTS) and DOES name the
            // conflicting paths, so this is the one pull() path where ConflictResolver
            // actually runs: the dirty local content gets a timestamped backup.
            assertTrue(
                "dirty-tree conflict must surface ResolvedWithConflicts, got $result",
                result is SyncResult.ResolvedWithConflicts
            )
            assertEquals(1, (result as SyncResult.ResolvedWithConflicts).backedUpFilesCount)

            val backups = conflictBackups()
            assertEquals(1, backups.size)
            val backup = backups.single()
            assertEquals(vaultFile(STORY).parentFile, backup.parentFile)
            assertTrue(
                "unexpected backup name: ${backup.name}",
                backup.name.matches(Regex("""story_sync_conflict_\d{8}_\d{6}\.md"""))
            )
            assertEquals(LOCAL, backup.readText())

            // The working tree was left exactly as it was: dirty edit intact,
            // HEAD unmoved, repository SAFE, nothing pulled, nothing pushed.
            assertEquals(LOCAL, vaultFile(STORY).readText())
            assertEquals(localHeadBefore, vaultHead())
            Git.open(vaultDir).use { git ->
                assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
                assertTrue(
                    "the local edit must still be pending",
                    git.status().call().modified.contains(STORY)
                )
            }
            assertEquals(remoteHeadBefore, remoteHead())
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Creates the bare remote seeded with one commit on `main` containing [files]. */
    private fun seedRemote(files: Map<String, String>) {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()

        val seedDir = temp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDir).call().use { git ->
            files.forEach { (path, content) ->
                File(seedDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            }
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("seed")
                .setAuthor("Seed", "seed@test.local")
                .setSign(false)
                .call()
            git.push()
                .setRemote(remoteUri)
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
    }

    /** Pushes a commit editing [path] to the bare remote from an independent clone. */
    private fun pushRemoteEdit(path: String, content: String) {
        val collabDir = temp.newFolder()
        Git.cloneRepository()
            .setURI(remoteUri)
            .setDirectory(collabDir)
            .setBranch("main")
            .call()
            .use { git ->
                File(collabDir, path).apply { parentFile?.mkdirs() }.writeText(content)
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("remote edit $path")
                    .setAuthor("Collab", "collab@test.local")
                    .setSign(false)
                    .call()
                git.push().call()
            }
    }

    private fun vaultFile(relativePath: String): File = File(vaultDir, relativePath)

    private fun vaultHead(): ObjectId =
        Git.open(vaultDir).use { requireNotNull(it.repository.resolve("HEAD")) }

    private fun remoteHead(): ObjectId =
        Git.open(remoteDir).use { requireNotNull(it.repository.resolve("refs/heads/main")) }

    /** All ConflictResolver backup files anywhere in the vault working tree. */
    private fun conflictBackups(): List<File> =
        vaultDir.walkTopDown()
            .filter { it.isFile && !it.path.contains("${File.separator}.git${File.separator}") }
            .filter { it.name.contains("_sync_conflict_") }
            .toList()

    private companion object {
        const val STORY = "notes/story.md"
        const val OTHER = "notes/autre.md"
        const val BASE = "ligne de base\n"
        const val LOCAL = "version locale precieuse\n"
        const val REMOTE = "version distante concurrente\n"
        const val REMOTE_Y = "version distante de Y\n"
    }

    /**
     * SystemReader that delegates to the real one but serves EMPTY user/system/jgit
     * configs from a temp dir, so tests never read the developer machine's gitconfig.
     */
    private class IsolatedSystemReader(
        private val delegate: SystemReader,
        private val configDir: File
    ) : SystemReader() {
        override fun getHostname(): String = delegate.hostname
        override fun getenv(variable: String): String? = delegate.getenv(variable)
        override fun getProperty(key: String): String? = delegate.getProperty(key)
        override fun getCurrentTime(): Long = delegate.currentTime

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getTimezone(whenTime: Long): Int = delegate.getTimezone(whenTime)

        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "user.gitconfig"), fs)

        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "jgit.gitconfig"), fs)
    }
}
