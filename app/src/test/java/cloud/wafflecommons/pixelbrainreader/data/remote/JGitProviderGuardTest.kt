package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.guard.AllowEntry
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.guard.ConfidentialityScanner
import cloud.wafflecommons.pixelbrainreader.data.guard.FindingKind
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardSettings
import cloud.wafflecommons.pixelbrainreader.data.guard.StagedText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The confidentiality guard on the REAL commit path: a held commit records nothing (so nothing can
 * be pushed), what HEAD already holds never holds later edits, and a commit message that would
 * name a protected term is replaced.
 */
class JGitProviderGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var remoteDir: File
    private lateinit var remoteUri: String
    private lateinit var provider: JGitProvider

    /** The real scanner with settings the test controls, instead of the encrypted on-device store. */
    private val guard = object : CommitGuard {
        var settings = GuardSettings(terms = listOf("Acme"), detectSecrets = true)
        override fun review(changes: List<StagedText>): List<GuardFinding> = ConfidentialityScanner.review(changes, settings)
        override fun mentionsProtectedTerm(message: String): Boolean = ConfidentialityScanner.mentionsAny(message, settings.terms)
    }

    @Before
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), temp.newFolder("gitconfigs")))
        filesDir = temp.newFolder("filesDir")
        remoteDir = temp.newFolder("remote.git")
        remoteUri = "file://" + remoteDir.absolutePath

        val context = mockk<Context> { every { filesDir } returns this@JGitProviderGuardTest.filesDir }
        val secretManager = mockk<SecretManager> {
            every { getToken() } returns "fake-token"
            every { getProvider() } returns "github"
        }
        provider = JGitProvider(context, secretManager, ConflictResolver(context), guard)
    }

    @After
    fun tearDown() {
        SystemReader.setInstance(null)
    }

    @Test
    fun `a commit adding a protected name is held and records nothing until allowed`() = runBlocking {
        seedRemote(mapOf(NOTE to "Kickoff\n"))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        vaultFile(NOTE).writeText("Kickoff\nCall Acme back\n")
        val result = provider.commit("edit note")

        val held = result.exceptionOrNull() as CommitHeldException
        assertEquals(listOf(NOTE to 2), held.findings.map { it.path to it.line })
        assertEquals(listOf("seed"), provider.getFileCommitHistory(NOTE).map { it.message })

        guard.settings = guard.settings.copy(allowed = listOf(AllowEntry(NOTE, "term:acme")))
        assertTrue(provider.commit("edit note").isSuccess)
        assertEquals(listOf("edit note", "seed"), provider.getFileCommitHistory(NOTE).map { it.message })
    }

    @Test
    fun `what HEAD already holds never holds a later edit`() = runBlocking {
        seedRemote(mapOf(NOTE to "Acme kickoff\n"))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        vaultFile(NOTE).writeText("Acme kickoff\nAgenda\n")
        assertTrue(provider.commit("edit note").isSuccess)
    }

    @Test
    fun `a secret in a new note is held`() = runBlocking {
        seedRemote(mapOf(NOTE to "Kickoff\n"))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        val inbox = "00_Inbox/env.md"
        vaultFile(inbox).apply { parentFile?.mkdirs() }.writeText("API_KEY=" + "a1b2c3d4e5f6g7h8" + "\n")
        val held = provider.commit("Auto-save: local changes").exceptionOrNull() as CommitHeldException

        assertEquals(listOf(inbox to FindingKind.SECRET), held.findings.map { it.path to it.kind })
        assertTrue(provider.getFileCommitHistory(inbox).isEmpty())
    }

    @Test
    fun `a commit message naming a protected term gets a neutral one`() = runBlocking {
        val clientNote = "20_Projects/Acme.md"
        seedRemote(mapOf(NOTE to "Kickoff\n", clientNote to "old notes\n"))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        vaultFile(clientNote).delete()
        assertTrue(provider.commit("Delete $clientNote").isSuccess)

        assertEquals(listOf("vault: update notes", "seed"), provider.getFileCommitHistory(clientNote).map { it.message })
    }

    @Test
    fun `checking pending changes reports what would be held without committing`() = runBlocking {
        seedRemote(mapOf(NOTE to "Kickoff\n"))
        assertTrue(provider.setupRepository(remoteUri, "main").isSuccess)

        vaultFile(NOTE).writeText("Kickoff\nAcme\n")
        val findings = provider.reviewPendingChanges().getOrThrow()

        assertEquals(1, findings.size)
        assertEquals(listOf("seed"), provider.getFileCommitHistory(NOTE).map { it.message })
    }

    private fun vaultFile(path: String): File = File(File(filesDir, "vault"), path)

    /** Creates the bare remote seeded with one commit on `main` holding [files]. */
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

    private companion object {
        const val NOTE = "20_Projects/plan.md"
    }
}
