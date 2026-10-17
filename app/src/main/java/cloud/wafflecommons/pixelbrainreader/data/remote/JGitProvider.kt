package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncResult {
    object Success : SyncResult()
    data class ResolvedWithConflicts(val backedUpFilesCount: Int) : SyncResult()
    data class Error(val exception: Exception) : SyncResult()
}

data class GitCommitDto(
    val commitId: String,
    val shortId: String,
    val author: String,
    val message: String,
    val timestamp: Long
)

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

    /**
     * Serializes every JGit write across all callers (background vault pushes, the foreground
     * sync cycle, file operations…). JGit's own .git/index.lock file is per-operation and gives a
     * hard failure on contention — this mutex prevents concurrent writers from racing into it.
     */
    private val gitMutex = Mutex()

    /**
     * A stale `.lock` can only be left behind by a process that died mid-operation: inside one
     * process every JGit op is serialized by [gitMutex] and JGit releases its locks in `finally`.
     * So the cleanup runs once per process (and again after a lock failure) instead of walking the
     * entire `.git` directory — every loose object included — before EVERY git operation, which
     * grew slower with each auto-commit.
     */
    @Volatile
    private var staleLocksChecked = false

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /** Removes stale '.lock' files (top-level, under refs/, and under logs/) left over from a crash. Call under [gitMutex]. */
    private fun cleanStaleLocksIfNeeded() {
        if (staleLocksChecked) return
        val gitDir = File(rootDir, ".git")
        if (!gitDir.isDirectory) return

        try {
            val topLevel = gitDir.listFiles { f -> f.isFile && f.name.endsWith(".lock") }.orEmpty().asSequence()
            val refs = File(gitDir, "refs").walkBottomUp().filter { it.isFile && it.name.endsWith(".lock") }
            val logsDir = File(gitDir, "logs")
            val logs = if (logsDir.exists()) {
                logsDir.walkBottomUp().filter { it.isFile && it.name.endsWith(".lock") }
            } else {
                emptySequence()
            }
            (topLevel + refs + logs).forEach { lockFile ->
                if (lockFile.delete()) {
                    Log.d("JGitProvider", "Cleaned stale lock file: ${lockFile.name}")
                }
            }
            staleLocksChecked = true
        } catch (e: Exception) {
            Log.e("JGitProvider", "Failed to clean stale locks", e)
        }
    }

    /**
     * Recovers a repository left in an interrupted rebase or merge state (e.g., if the process died
     * mid-rebase) so subsequent commits and pulls do not stay permanently wedged.
     */
    private fun recoverRepositoryStateIfNeeded(git: Git) {
        val state = git.repository.repositoryState
        if (state == org.eclipse.jgit.lib.RepositoryState.SAFE) return
        if (state.isRebasing ||
            state == org.eclipse.jgit.lib.RepositoryState.MERGING ||
            state == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED
        ) {
            Log.w("JGitProvider", "Recovering repository from non-safe state: $state")
            try {
                git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call()
            } catch (e: Exception) {
                Log.w("JGitProvider", "Rebase abort during state recovery failed; cleaning state dirs manually", e)
            }
            val gitDir = git.repository.directory
            listOf("rebase-merge", "rebase-apply").forEach { dirName ->
                val stateDir = File(gitDir, dirName)
                if (stateDir.exists()) {
                    stateDir.deleteRecursively()
                }
            }
        }
    }

    /** A lock failure means a stale lock may exist after all: re-check before the next operation. */
    private fun onGitFailure(e: Exception) {
        val lockRelated = e.javaClass.simpleName.contains("Lock", ignoreCase = true) ||
            e.message?.contains("lock", ignoreCase = true) == true
        if (lockRelated) staleLocksChecked = false
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
            cleanStaleLocksIfNeeded()
            try {
                if (isReady()) {
                    // Already initialized
                    return@withLock Result.success(Unit)
                }

                if (!remoteUrl.isNullOrEmpty()) {
                    // CLONE STRATEGY
                    Log.d("JGitProvider", "Starting fresh clone from $remoteUrl branch: $branch")
                    val provider = getCredentialsProvider() ?: throw Exception("Clone requires API Token")

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
                            .setTimeout(NETWORK_TIMEOUT_SECONDS)
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
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    // Keep initRepository for backward compatibility/local-init fallback
    suspend fun initRepository(): Result<Unit> = setupRepository(null)

    /**
     * Stages all changes (git add .). [commit] already stages everything itself, so callers that
     * commit right afterwards don't need this.
     */
    suspend fun addAll(): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    /**
     * Removes a file from the git index.
     */
    suspend fun removeFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    git.rm().addFilepattern(relativePath).setCached(true).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    /**
     * Adds a specific file to the git index.
     */
    suspend fun addFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    git.add().addFilepattern(relativePath).call()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    /**
     * Stages everything (new, modified and deleted files) and commits it. A no-op when nothing
     * changed. Handles Detached HEAD by switching back to main first.
     */
    suspend fun commit(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    recoverRepositoryStateIfNeeded(git)

                    // 1. Fix Detached HEAD
                    val repo = git.repository
                    val branch = repo.branch
                    // ObjectId.isId(branch) checks if it's a commit hash (detached) vs a ref name
                    if (org.eclipse.jgit.lib.ObjectId.isId(branch)) {
                        Log.w("JGitProvider", "Detached HEAD detected ($branch). Checking out 'main'...")
                        git.checkout().setName("main").call()
                    }

                    // 2. Stage All: new/modified files, then deletions.
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()

                    // A repository without any commit yet can't be compared to HEAD; keep the old
                    // "nothing to commit" behaviour there with an explicit status check.
                    val hasHead = repo.resolve("HEAD") != null
                    if (!hasHead && git.status().call().isClean) {
                        Log.d("JGitProvider", "No changes to commit after adding.")
                        return@use
                    }

                    // 3. Commit with semantic message if message is generic.
                    val finalMessage = if (cloud.wafflecommons.pixelbrainreader.data.sync.SemanticCommitMessageGenerator.isGenericMessage(message)) {
                        val status = git.status().call()
                        val dirtyPaths = (status.uncommittedChanges + status.changed + status.added + status.modified)
                        cloud.wafflecommons.pixelbrainreader.data.sync.SemanticCommitMessageGenerator.generate(dirtyPaths, message)
                    } else {
                        message
                    }

                    try {
                        val revCommit = git.commit()
                            .setMessage(finalMessage)
                            .setAuthor("PixelBrain User", "user@pixelbrain.local")
                            .setAllowEmpty(false)
                            .call()
                        Log.d("JGitProvider", "Committed: $finalMessage (Hash: ${revCommit.name})")
                    } catch (e: EmptyCommitException) {
                        Log.d("JGitProvider", "No changes to commit after adding.")
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Log.e("JGitProvider", "Commit Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Pushes to remote. Fails when the remote refuses the update (see [describeRejectedUpdates]).
     */
    suspend fun push(remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))

        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                val provider = getCredentialsProvider() ?: return@withContext Result.failure(Exception("No API Token found"))

                val results = Git.open(rootDir).use { git ->
                    git.push()
                        .setRemote(remoteName)
                        .setCredentialsProvider(provider)
                        .setTimeout(NETWORK_TIMEOUT_SECONDS)
                        .setProgressMonitor(AndroidLogProgressMonitor())
                        .call()
                }
                val rejection = describeRejectedUpdates(results)
                if (rejection != null) {
                    Log.e("JGitProvider", "Push rejected: $rejection")
                    return@withLock Result.failure(Exception("Push rejected: $rejection"))
                }
                Log.i("JGitProvider", "Push Successful")
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
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
            cleanStaleLocksIfNeeded()
            try {
                ensureCriticalDirectories() // Defensive check before pulling

            val provider = getCredentialsProvider() ?: return@withContext SyncResult.Error(Exception("No API Token found"))

            Git.open(rootDir).use { git ->
                recoverRepositoryStateIfNeeded(git)
                val pullResult = git.pull()
                    .setRemote(remoteName)
                    .setRebase(true) // STRICT REBASE — no merge commits
                    .setCredentialsProvider(provider)
                    .setTimeout(NETWORK_TIMEOUT_SECONDS)
                    .setProgressMonitor(AndroidLogProgressMonitor())
                    .call()

                val rebaseResult = pullResult.rebaseResult
                if (rebaseResult != null) {
                    when (rebaseResult.status) {
                        org.eclipse.jgit.api.RebaseResult.Status.OK,
                        org.eclipse.jgit.api.RebaseResult.Status.UP_TO_DATE,
                        org.eclipse.jgit.api.RebaseResult.Status.FAST_FORWARD,
                        org.eclipse.jgit.api.RebaseResult.Status.NOTHING_TO_COMMIT -> {
                            Log.i("JGitProvider", "Pull (rebase) successful: ${rebaseResult.status}")
                        }
                        org.eclipse.jgit.api.RebaseResult.Status.UNCOMMITTED_CHANGES -> {
                            // The rebase never started (dirty tree blocked it): nothing was
                            // pulled, so reporting Success would silently stall sync forever.
                            Log.w("JGitProvider", "Pull blocked by uncommitted local changes; nothing pulled.")
                            return@withContext SyncResult.Error(Exception("Pull blocked by uncommitted local changes"))
                        }
                        org.eclipse.jgit.api.RebaseResult.Status.STOPPED,
                        org.eclipse.jgit.api.RebaseResult.Status.CONFLICTS,
                        org.eclipse.jgit.api.RebaseResult.Status.FAILED -> {
                            Log.w("JGitProvider", "Rebase conflict/failure: ${rebaseResult.status}. Aborting, then backing up clean local copies.")

                            // STOPPED (committed-vs-committed conflict) reports null via
                            // getConflicts(); the unmerged paths are only visible in git
                            // status WHILE the rebase is in progress — read them BEFORE
                            // the abort wipes that state, back them up after it.
                            val conflicts = rebaseResult.conflicts
                                ?: try {
                                    git.status().call().conflicting.toList()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    emptyList()
                                }

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
                     onGitFailure(e)
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
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    val config = git.repository.config
                    config.setString("remote", remoteName, "url", url)
                    config.save()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
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
            cleanStaleLocksIfNeeded()
            try {
                val provider = getCredentialsProvider() ?: return@withLock Result.failure(Exception("No API Token found"))

                val results = Git.open(rootDir).use { git ->
                    // Step A: Add All
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()

                    // Step B: Commit
                    try {
                        git.commit()
                            .setMessage(message)
                            .setAuthor("PixelBrain User", "user@pixelbrain.local")
                            .setAllowEmpty(false)
                            .call()
                        Log.i("GitSync", "Emergency Commit Created")
                    } catch (e: EmptyCommitException) {
                        Log.d("GitSync", "Nothing to commit before the force push")
                    }

                    // Step C: Force Push
                    git.push()
                        .setRemote("origin")
                        .setCredentialsProvider(provider)
                        .setForce(true) // FORCE PUSH
                        .setTimeout(NETWORK_TIMEOUT_SECONDS)
                        .setProgressMonitor(AndroidLogProgressMonitor())
                        .call()
                }
                val rejection = describeRejectedUpdates(results)
                if (rejection != null) {
                    Log.e("GitSync", "Force push rejected: $rejection")
                    return@withLock Result.failure(Exception("Force push rejected: $rejection"))
                }
                Log.w("GitSync", "EMERGENCY FORCE PUSH COMPLETED")
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Log.e("GitSync", "Force Push Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * JGit does NOT throw when the remote refuses a ref update (non-fast-forward, protected branch,
     * hook rejection…) — it reports it per ref in the [PushResult]s. Treating a completed call as
     * success reported sync as healthy while every push was actually being refused.
     *
     * @return a human-readable description of the refused updates, or null when all went through.
     */
    private fun describeRejectedUpdates(results: Iterable<PushResult>): String? {
        val rejected = results.flatMap { it.remoteUpdates }.filter { update ->
            update.status != RemoteRefUpdate.Status.OK && update.status != RemoteRefUpdate.Status.UP_TO_DATE
        }
        if (rejected.isEmpty()) return null
        return rejected.joinToString { update ->
            buildString {
                append(update.remoteName).append(": ").append(update.status)
                update.message?.let { append(" (").append(it).append(')') }
            }
        }
    }

    /**
     * Resolves a [UsernamePasswordCredentialsProvider] using the stored token.
     * "token" is the robust HTTPS username for a GitHub PAT/OAuth token (also for org-owned
     * repositories); GitLab expects "oauth2".
     */
    private fun getCredentialsProvider(): UsernamePasswordCredentialsProvider? {
        val token = secretManager.getToken() ?: return null
        val providerType = secretManager.getProvider()

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

    /**
     * Retrieve the commit history for a single file in the local vault.
     */
    suspend fun getFileCommitHistory(relativePath: String, limit: Int = 15): List<GitCommitDto> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            val gitDir = File(rootDir, ".git")
            if (!gitDir.isDirectory) return@withContext emptyList()
            try {
                Git.open(rootDir).use { git ->
                    val logs = git.log().addPath(relativePath).setMaxCount(limit).call()
                    logs.map { commit ->
                        GitCommitDto(
                            commitId = commit.id.name,
                            shortId = commit.id.abbreviate(7).name(),
                            author = commit.authorIdent.name,
                            message = commit.shortMessage,
                            timestamp = commit.commitTime.toLong() * 1000L
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("JGitProvider", "Failed to get file commit history for $relativePath", e)
                emptyList()
            }
        }
    }

    /**
     * Retrieve the file content at a specific Git commit revision.
     */
    suspend fun getFileContentAtCommit(relativePath: String, commitId: String): String? = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            val gitDir = File(rootDir, ".git")
            if (!gitDir.isDirectory) return@withContext null
            try {
                Git.open(rootDir).use { git ->
                    val repo = git.repository
                    val objectId = org.eclipse.jgit.lib.ObjectId.fromString(commitId)
                    val revCommit = repo.parseCommit(objectId)
                    val tree = revCommit.tree
                    org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, relativePath, tree)?.use { treeWalk ->
                        val blobId = treeWalk.getObjectId(0)
                        val loader = repo.open(blobId)
                        String(loader.bytes, Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("JGitProvider", "Failed to retrieve content for $relativePath at $commitId", e)
                null
            }
        }
    }

    private companion object {
        /**
         * Socket inactivity timeout for clone/pull/push. JGit defaults to 0 = none, and Android's
         * HttpURLConnection is also unbounded, so one stalled connection (network handover, Wi-Fi
         * dropping on resume) used to hang the operation forever while holding the git locks —
         * blocking every later save and leaving the sync indicator spinning.
         */
        const val NETWORK_TIMEOUT_SECONDS = 30
    }
}
