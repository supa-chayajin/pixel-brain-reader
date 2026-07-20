package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.api.CheckoutCommand
import java.io.File
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class SyncResult {
    object Success : SyncResult()
    data class ResolvedWithConflicts(val backedUpFilesCount: Int) : SyncResult()
    data class Error(val exception: Exception) : SyncResult()
}

/**
 * Local-First Git Provider using Eclipse JGit.
 * Operates directly on the device filesystem.
 */
@Singleton
class JGitProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretManager: SecretManager,
    private val conflictResolver: ConflictResolver
) {

    // Stale .git/*.lock cleanup is deliberately NOT done in the constructor: as a
    // @Singleton, first injection can land on the main thread (e.g. a ViewModel
    // built during composition), and walking the .git tree there is main-thread
    // disk I/O. Every git operation below calls cleanStaleLocks() under gitMutex,
    // which already covers the crashed-with-held-lock recovery case.

    /**
     * Serializes every JGit write across all callers (HabitRepository background
     * push, SyncOrchestrator foreground sync, NoteRepository save, etc.). JGit's
     * own .git/index.lock file is per-operation and gives a hard failure on
     * contention — this mutex prevents concurrent writers from racing into it.
     * cleanStaleLocks() runs under the lock so a previously-crashed lock from
     * a process that died with a held mutex still gets cleared.
     */
    private val gitMutex = Mutex()

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /**
     * Removes stale '.lock' files left over from crashes or restore operations.
     */
    private fun cleanStaleLocks() {
        val gitDir = File(rootDir, ".git")
        if (!gitDir.exists()) return

        try {
            gitDir.walkBottomUp()
                .filter { it.isFile && it.name.endsWith(".lock") }
                .forEach { lockFile ->
                    val deleted = lockFile.delete()
                    if (deleted) {
                        Log.d("JGitProvider", "Cleaned stale lock file: ${lockFile.absolutePath}")
                    }
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("JGitProvider", "Failed to clean stale locks", e)
        }
    }

    /**
     * Ensures critical directories exist to prevent JGit errors.
     */
    private fun ensureCriticalDirectories() {
        if (!rootDir.exists()) rootDir.mkdirs()
        File(rootDir, "10_Journal/system").mkdirs()
    }

    /**
     * Set up the repository.
     * Strategies:
     * 1. If repo exists -> Open it.
     * 2. If repo missing & URL provided -> Clone it (First Sync).
     * 3. If repo missing & No URL -> Init new local repo.
     */
    suspend fun setupRepository(remoteUrl: String?, branch: String = "main"): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                if (isReady()) {
                    // Already initialized
                    return@withLock Result.success(Unit)
                }

                if (!remoteUrl.isNullOrEmpty()) {
                    // CLONE STRATEGY
                    Log.i("JGitProvider", "Starting fresh clone from $remoteUrl branch: $branch")
                    val provider = getCredentialsProvider(remoteUrl) ?: throw Exception("Clone requires API Token")

                    // Ensure clean slate
                    if (rootDir.exists()) {
                        rootDir.deleteRecursively()
                    }
                    rootDir.mkdirs()

                    try {
                        Git.cloneRepository()
                            .setURI(remoteUrl)
                            .setDirectory(rootDir)
                            .setBranch(branch)
                            .setCredentialsProvider(provider)
                            .setProgressMonitor(AndroidLogProgressMonitor())
                            .call()
                            .close() // Close Git instance

                        Log.i("JGitProvider", "Clone successful.")
                        Result.success(Unit)
                    } catch (e: Exception) {
                        Log.e("JGitProvider", "Clone failed. Cleaning up.", e)
                        // ATOMICITY: Delete broken repo to avoid "corrupted" state on next run
                        rootDir.deleteRecursively()
                        throw e
                    }
                } else {
                    // INIT STRATEGY (Local-only start)
                    Log.i("JGitProvider", "Initializing new local repository.")
                    rootDir.mkdirs()
                    Git.init().setDirectory(rootDir).call().close()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    // Keep initRepository for backward compatibility/local-init fallback
    suspend fun initRepository(): Result<Unit> = setupRepository(null)

    /**
     * Stages all changes (git add .).
     */
    suspend fun addAll(): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                Git.open(rootDir).use { git ->
                    // Directive: Use git.add().addFilepattern(".").call() effectively acting as a git add .
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    /**
     * Removes a file from the git index.
     */
    suspend fun removeFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                Git.open(rootDir).use { git ->
                    git.rm().addFilepattern(relativePath).setCached(true).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    /**
     * Adds a specific file to the git index.
     */
    suspend fun addFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                Git.open(rootDir).use { git ->
                    git.add().addFilepattern(relativePath).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    /**
     * Commits staged changes.
     */
    /**
     * Commits staged changes.
     * Includes "Force Add" to ensure new files (especially in subdirs) are caught.
     * Handles Detached HEAD by switching to main if needed.
     */
    suspend fun commit(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                Git.open(rootDir).use { git ->
                    // 1. Fix Detached HEAD
                    val repo = git.repository
                    val branch = repo.branch
                    // ObjectId.isId(branch) checks if it's a commit hash (detached) vs a ref name
                    if (org.eclipse.jgit.lib.ObjectId.isId(branch)) {
                        Log.w("JGitProvider", "Detached HEAD detected ($branch). Checking out 'main'...")
                        git.checkout().setName("main").call()
                    }

                    // 2. Force Add (Stage All)
                    // Add new/modified files
                    git.add().addFilepattern(".").call()
                    // Stage deletions
                    git.add().addFilepattern(".").setUpdate(true).call()

                    // 3. Commit
                    val status = git.status().call()
                    if (status.hasUncommittedChanges()) {
                        val revCommit = git.commit()
                            .setMessage(message)
                            .setAuthor("PixelBrain User", "user@pixelbrain.local")
                            .call()
                        Log.i("JGitProvider", "Committed: $message (Hash: ${revCommit.name})")
                    } else {
                        Log.d("JGitProvider", "No changes to commit after adding.")
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("JGitProvider", "Commit Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Pushes to remote.
     */
    suspend fun push(remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))

        gitMutex.withLock {
            cleanStaleLocks()
            try {
                val provider = getCredentialsProvider() ?: return@withContext Result.failure(Exception("No API Token found"))

                Git.open(rootDir).use { git ->
                    git.push()
                        .setRemote(remoteName)
                        .setCredentialsProvider(provider)
                        .setProgressMonitor(AndroidLogProgressMonitor())
                        .call()
                }
                Log.i("JGitProvider", "Push Successful")
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("JGitProvider", "Push Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Pulls from remote using REBASE strategy (no merge commits allowed).
     * Handles rebase conflicts gracefully: backs up local changes, aborts rebase, and reports.
     */
    suspend fun pull(remoteName: String = "origin"): SyncResult = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext SyncResult.Error(Exception("Repository not initialized"))

        gitMutex.withLock {
            cleanStaleLocks()
            try {
                ensureCriticalDirectories() // Defensive check before pulling

            val provider = getCredentialsProvider() ?: return@withContext SyncResult.Error(Exception("No API Token found"))

            Git.open(rootDir).use { git ->
                val pullResult = git.pull()
                    .setRemote(remoteName)
                    .setRebase(true) // STRICT REBASE — no merge commits
                    .setCredentialsProvider(provider)
                    .setProgressMonitor(AndroidLogProgressMonitor())
                    .call()

                val rebaseResult = pullResult.rebaseResult
                if (rebaseResult != null) {
                    when (rebaseResult.status) {
                        org.eclipse.jgit.api.RebaseResult.Status.OK,
                        org.eclipse.jgit.api.RebaseResult.Status.UP_TO_DATE,
                        org.eclipse.jgit.api.RebaseResult.Status.FAST_FORWARD -> {
                            Log.i("JGitProvider", "Pull (rebase) successful: ${rebaseResult.status}")
                        }
                        org.eclipse.jgit.api.RebaseResult.Status.STOPPED,
                        org.eclipse.jgit.api.RebaseResult.Status.CONFLICTS,
                        org.eclipse.jgit.api.RebaseResult.Status.FAILED -> {
                            Log.w("JGitProvider", "Rebase conflict/failure: ${rebaseResult.status}. Aborting, then backing up clean local copies.")

                            val conflicts = rebaseResult.conflicts ?: emptyList()

                            // Abort FIRST so the working tree is restored to the clean
                            // pre-pull local state. Backing up BEFORE the abort captures
                            // JGit's <<<<<<< / >>>>>>> conflict markers instead of the
                            // user's actual local content — useless for recovery.
                            try {
                                git.rebase()
                                    .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT)
                                    .call()
                                Log.i("JGitProvider", "Rebase aborted successfully.")
                            } catch (abortEx: Exception) {
                                Log.e("JGitProvider", "Failed to abort rebase", abortEx)
                            }

                            // Now snapshot the (restored, clean) local versions so the
                            // user has a recoverable copy of their diverged work.
                            var backedUpCount = 0
                            for (relativePath in conflicts) {
                                val backup = conflictResolver.secureLocalBackup(relativePath)
                                if (backup != null) backedUpCount++
                            }
                            Log.i("JGitProvider", "$backedUpCount clean local file(s) backed up.")

                            return@withContext SyncResult.ResolvedWithConflicts(backedUpCount)
                        }
                        else -> {
                            Log.w("JGitProvider", "Unexpected rebase status: ${rebaseResult.status}")
                        }
                    }
                }
            }
            Log.i("JGitProvider", "Pull Successful")
            return@withContext SyncResult.Success
            } catch (e: Exception) {
                 if (e is CancellationException) throw e
                 if (e is RefNotAdvertisedException) {
                     Log.w("JGitProvider", "Pull skipped: Ref not advertised (Empty repo?)")
                     return@withContext SyncResult.Success
                 } else {
                     Log.e("JGitProvider", "Pull Failed", e)
                     return@withContext SyncResult.Error(e)
                 }
            }
        }
        // gitMutex closed; unreachable but required for compiler.
        SyncResult.Success
    }

    /**
     * Configures the remote URL.
     */
    suspend fun setRemote(url: String, remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repo not ready"))
        // Mutate .git/config under the same gitMutex every other write uses — otherwise a
        // concurrent pull()/push() reading remote.<name>.url can race a half-written config
        // or collide on .git/config.lock.
        gitMutex.withLock {
            cleanStaleLocks()
            try {
                Git.open(rootDir).use { git ->
                    val config = git.repository.config
                    config.setString("remote", remoteName, "url", url)
                    config.save()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }
    
    // Kept for signature compatibility, but setupRepository handles this now
    suspend fun cloneRepo(url: String): Result<Unit> = setupRepository(url)

    fun isReady(): Boolean = File(rootDir, ".git").exists()

    /**
     * EMERGENCY SYNC: Commits all changes and FORCE PUSHES to remote.
     * This overrides the remote state with the local state.
     * Use with caution.
     */
    suspend fun commitAndForcePush(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.w("GitSync", "Starting EMERGENCY FORCE PUSH")
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))

        gitMutex.withLock {
            cleanStaleLocks()
            try {
                val provider = getCredentialsProvider() ?: return@withLock Result.failure(Exception("No API Token found"))

                Git.open(rootDir).use { git ->
                    // Step A: Add All
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()

                    // Step B: Commit
                    val status = git.status().call()
                    if (status.hasUncommittedChanges()) {
                        git.commit()
                            .setMessage(message)
                            .setAuthor("PixelBrain User", "user@pixelbrain.local")
                            .call()
                        Log.i("GitSync", "Emergency Commit Created")
                    }

                    // Step C: Force Push
                    git.push()
                        .setRemote("origin")
                        .setCredentialsProvider(provider)
                        .setForce(true) // FORCE PUSH
                        .setProgressMonitor(AndroidLogProgressMonitor())
                        .call()
                }
                Log.w("GitSync", "EMERGENCY FORCE PUSH COMPLETED")
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("GitSync", "Force Push Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Resolves a [UsernamePasswordCredentialsProvider] using the stored token and owner.
     * If owner is not explicitly stored, attempts to extract it from the remote URL.
     * Falling back to "token" as username if all else fails (compatible with some PATs).
     */
    private fun getCredentialsProvider(remoteUrl: String? = null): UsernamePasswordCredentialsProvider? {
        val token = secretManager.getToken() ?: return null
        val providerType = secretManager.getProvider()

        // Using "token" as the username is the standard, most robust way to authenticate via HTTPS with a PAT
        // on GitHub, and "oauth2" for GitLab, especially when the repository is owned by an organization or a different user.
        val finalUsername = if (providerType.equals("gitlab", ignoreCase = true)) {
            "oauth2"
        } else {
            "token"
        }

        Log.d("JGitProvider", "Using username: $finalUsername for authentication")
        return UsernamePasswordCredentialsProvider(finalUsername, token)
    }

    /**
     * Custom Progress Monitor that logs to Logcat
     */
    private class AndroidLogProgressMonitor : TextProgressMonitor(LogWriter()) {
        class LogWriter : Writer() {
            private val buffer = StringBuilder()
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                buffer.append(cbuf, off, len)
                if (buffer.contains("\n")) {
                    flush()
                }
            }
            override fun flush() {
                if (buffer.isNotEmpty()) {
                    Log.d("JGitProgress", buffer.toString().trim())
                    buffer.clear()
                }
            }
            override fun close() { flush() }
        }
    }
}
