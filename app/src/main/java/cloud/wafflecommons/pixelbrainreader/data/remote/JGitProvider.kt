package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.ReadOnlyDeviceException
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitGuard
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.data.guard.StagedText
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChangedException
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChoice
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictFile
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictResolver
import cloud.wafflecommons.pixelbrainreader.data.sync.PendingConflict
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncConflicts
import cloud.wafflecommons.pixelbrainreader.data.sync.SemanticCommitMessageGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
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
    /** A conflict is waiting for the owner's choices (12.15, S1): the pull didn't run. */
    data class ConflictPending(val paths: List<String>) : SyncResult()
    data class Error(val exception: Exception) : SyncResult()
}

/** A commit for the activity views: when, by whom, its subject and the paths it touched. */
data class CommitSummary(val id: String, val timeMillis: Long, val author: String, val message: String, val paths: List<String>)

/** A path a pull changed, and how: "add", "modify", "delete" or "rename". */
data class ChangedPath(val path: String, val change: String)

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
    @param:ApplicationContext private val context: Context,
    private val secretManager: SecretManager,
    private val conflictResolver: ConflictResolver,
    private val commitGuard: CommitGuard
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
                    cloneNewestCommit(remoteUrl, branch, provider)
                    Result.success(Unit)
                } else {
                    // A read-only device only ever holds a clone of GitHub.
                    if (VaultWriteGate.readOnly) return@withLock Result.failure(ReadOnlyDeviceException())
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

    /**
     * The first clone takes the newest commit only: the notes as they are now, a small transfer the
     * slowest connection gets through. A full clone was one transfer of the whole history, and a
     * network that drops a slow, long transfer (2026-11-11: three logins in a row, reset 7 to 10
     * minutes in) failed the login and started it over from zero. A dropped attempt is retried;
     * the history follows in small resumable steps ([deepenHistory]). Call under [gitMutex].
     */
    private suspend fun cloneNewestCommit(remoteUrl: String, branch: String, provider: UsernamePasswordCredentialsProvider) {
        var attempt = 0
        while (true) {
            attempt++
            if (rootDir.exists()) rootDir.deleteRecursively()
            rootDir.mkdirs()
            try {
                val started = System.currentTimeMillis()
                Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(rootDir)
                    .setBranch(branch)
                    .setDepth(1)
                    .setCredentialsProvider(provider)
                    .setTimeout(NETWORK_TIMEOUT_SECONDS)
                    .setProgressMonitor(AndroidLogProgressMonitor())
                    .call()
                    .close()
                Log.i("JGitProvider", "Clone of the newest commit done in ${(System.currentTimeMillis() - started) / 1000} s (attempt $attempt); the history follows in steps.")
                return
            } catch (e: Exception) {
                // ATOMICITY: never leave a half-cloned repository for the next run to trust.
                rootDir.deleteRecursively()
                if (e is CancellationException) throw e
                if (attempt >= CLONE_ATTEMPTS || !isTransientNetworkError(e)) {
                    Log.e("JGitProvider", "Clone failed (attempt $attempt).", e)
                    throw e
                }
                Log.w("JGitProvider", "Clone attempt $attempt dropped (${e.message}); retrying.")
                delay(RETRY_BACKOFF_MS * attempt * attempt)
            }
        }
    }

    /** How far a shallow clone's history has come: [complete] once nothing is missing. */
    data class HistoryProgress(val complete: Boolean, val commits: Int)

    /**
     * Fetches the next [step] commits of the history a shallow clone left behind; nothing
     * when the history is complete. Each step is its own transfer: a dropped connection loses that
     * step only, never what the steps before brought (they are in the object store, and
     * `.git/shallow` moves back). Like any fetch it updates `origin/<branch>`, never the branch or
     * the working tree. A read-only device runs it too: it only adds objects.
     */
    suspend fun deepenHistory(remoteName: String = "origin", step: Int = HISTORY_STEP): Result<HistoryProgress> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    val repo = git.repository
                    if (repo.objectDatabase.shallowCommits.isEmpty()) {
                        return@withLock Result.success(HistoryProgress(complete = true, commits = localHistory(repo).commits))
                    }
                    val provider = getCredentialsProvider() ?: return@withLock Result.failure(Exception("No API Token found"))
                    val before = repairShallowBoundary(repo)
                    try {
                        // Deeper than what is here (the count is at least the current depth): never shortens.
                        git.fetch()
                            .setRemote(remoteName)
                            .setCredentialsProvider(provider)
                            .setDepth(before.commits + step)
                            .setTimeout(NETWORK_TIMEOUT_SECONDS)
                            .setProgressMonitor(AndroidLogProgressMonitor())
                            .call()
                    } catch (e: Exception) {
                        // JGit already wrote the boundary the server announced, for a pack that never
                        // came: put back the one that matches what is here.
                        try {
                            repo.objectDatabase.setShallowCommits(before.edge)
                        } catch (restore: java.io.IOException) {
                            Log.w("JGitProvider", "Boundary not restored, the next step repairs it: ${restore.message}")
                        }
                        throw e
                    }
                    val after = repairShallowBoundary(repo)
                    Result.success(HistoryProgress(complete = after.edge.isEmpty(), commits = after.commits))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("JGitProvider", "History step failed", e)
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    /** The commits actually here: how many, and those whose parents aren't ([edge], a shallow clone's boundary). */
    private class LocalHistory(val commits: Int, val edge: Set<org.eclipse.jgit.lib.ObjectId>)

    /**
     * Walks the commits that are here from HEAD and the remote-tracking branches, reading each one
     * raw: never through `.git/shallow`, which can be wrong (see [repairShallowBoundary]).
     */
    private fun localHistory(repo: org.eclipse.jgit.lib.Repository): LocalHistory {
        val tips = listOfNotNull(repo.resolve("HEAD")) + repo.refDatabase.getRefsByPrefix("refs/remotes/").mapNotNull { it.objectId }
        val seen = HashSet<org.eclipse.jgit.lib.ObjectId>()
        val edge = HashSet<org.eclipse.jgit.lib.ObjectId>()
        val pending = ArrayDeque<org.eclipse.jgit.lib.ObjectId>(tips.map { it.copy() })
        repo.newObjectReader().use { reader ->
            // A walk without a reader never reads the shallow file: each commit keeps its real parents.
            val parser = org.eclipse.jgit.revwalk.RevWalk(null as org.eclipse.jgit.lib.ObjectReader?)
            while (pending.isNotEmpty()) {
                val id = pending.removeLast()
                if (id in seen || !reader.has(id, org.eclipse.jgit.lib.Constants.OBJ_COMMIT)) continue
                seen += id
                val raw = reader.open(id, org.eclipse.jgit.lib.Constants.OBJ_COMMIT).cachedBytes
                for (parent in org.eclipse.jgit.revwalk.RevCommit.parse(parser, raw).parents) {
                    if (reader.has(parent, org.eclipse.jgit.lib.Constants.OBJ_COMMIT)) pending += parent.copy() else edge += id
                }
            }
        }
        return LocalHistory(seen.size, edge)
    }

    /**
     * Makes `.git/shallow` list exactly the commits here whose parents aren't. JGit 6.8 writes the
     * boundary the server announces as soon as it reads it, BEFORE the pack arrives: a history step
     * whose transfer dropped (2026-11-11, on a slow emulator) left a boundary for commits that never
     * came, and every walk then failed on a missing parent ("Walk failure"), the pulls included.
     * Call under [gitMutex]; only worth it on a shallow clone.
     */
    private fun repairShallowBoundary(repo: org.eclipse.jgit.lib.Repository): LocalHistory {
        val history = localHistory(repo)
        val listed = repo.objectDatabase.shallowCommits
        if (listed != history.edge) {
            Log.w("JGitProvider", "Shallow boundary repaired: ${listed.size} listed, ${history.edge.size} actually at the edge")
            repo.objectDatabase.setShallowCommits(history.edge)
        }
        return history
    }

    /** Before a pull: a shallow clone left inconsistent by a dropped history step is repaired first. */
    private fun repairShallowBoundaryIfShallow() {
        try {
            Git.open(rootDir).use { git ->
                if (git.repository.objectDatabase.shallowCommits.isNotEmpty()) repairShallowBoundary(git.repository)
            }
        } catch (e: java.io.IOException) {
            Log.w("JGitProvider", "Shallow boundary check skipped: ${e.message}")
        }
    }

    /** The commits a shallow history stops at (their parents aren't here): empty once complete. */
    private fun shallowBoundary(repo: org.eclipse.jgit.lib.Repository): Set<org.eclipse.jgit.lib.ObjectId> =
        runCatching { repo.objectDatabase.shallowCommits }.getOrDefault(emptySet())

    // Keep initRepository for backward compatibility/local-init fallback
    suspend fun initRepository(): Result<Unit> = setupRepository(null)

    /**
     * Stages all changes (git add .). [commit] already stages everything itself, so callers that
     * commit right afterwards don't need this.
     */
    suspend fun addAll(): Result<Unit> = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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
        // A read-only device never stages nor commits anything.
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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

                    // 2b. Confidentiality guard, fail-closed: a finding holds the whole commit. The
                    // changes stay staged on the device until the user reviews them.
                    val held = commitGuard.review(stagedTexts(repo))
                    if (held.isNotEmpty()) throw CommitHeldException(held)

                    // 3. Commit. A generic message ("Auto-save…") is replaced by one naming what
                    // changed, read from the index vs HEAD — never from `git status`, which would
                    // walk the whole working tree a third time on every background commit. A message
                    // naming a protected term (a file name in "Delete …") gets a neutral one instead.
                    val namedMessage = if (hasHead && SemanticCommitMessageGenerator.isGenericMessage(message)) {
                        SemanticCommitMessageGenerator.generate(stagedPaths(repo), message)
                    } else {
                        message
                    }
                    val finalMessage = if (commitGuard.mentionsProtectedTerm(namedMessage)) NEUTRAL_COMMIT_MESSAGE else namedMessage

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
            } catch (e: CommitHeldException) {
                Log.w("JGitProvider", "Commit held by the confidentiality guard: ${e.findings.size} finding(s)")
                Result.failure(e)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Log.e("JGitProvider", "Commit Failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Stages everything and runs the confidentiality guard WITHOUT committing — the review screen's
     * "Check again". Returns what would hold the next commit (empty: it would go through).
     */
    suspend fun reviewPendingChanges(): Result<List<GuardFinding>> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.success(emptyList())
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                Git.open(rootDir).use { git ->
                    recoverRepositoryStateIfNeeded(git)
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()
                    Result.success(commitGuard.review(stagedTexts(git.repository)))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onGitFailure(e)
                Result.failure(e)
            }
        }
    }

    /**
     * Pushes to remote. Fails when the remote refuses the update (see [describeRejectedUpdates]).
     */
    suspend fun push(remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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

    /** The conflict the last pull stopped on, waiting for the owner's choices (S1); null when none. */
    fun pendingConflict(): PendingConflict? = if (isReady()) SyncConflicts.read(File(rootDir, ".git")) else null

    /** The pending conflict's files, read in core against the GitHub state the stopped pull fetched. */
    suspend fun conflictFiles(remoteName: String = "origin"): Result<List<ConflictFile>> = withContext(Dispatchers.IO) {
        if (pendingConflict() == null) return@withContext Result.success(emptyList())
        gitMutex.withLock {
            try {
                Git.open(rootDir).use { git ->
                    val upstream = git.repository.resolve("refs/remotes/$remoteName/${git.repository.branch}")
                        ?: return@withLock Result.failure(Exception("GitHub's branch isn't known yet"))
                    Result.success(SyncConflicts.inspect(git.repository, upstream))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("JGitProvider", "Reading the conflict failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Resolves the pending conflict (S1, owner 2026-11-11): fetch, fold the phone's unpushed commits
     * into one, replay it on top of GitHub's with each file's [choices], the confidentiality guard
     * on the result, delete the conflict's backups, push. Success carries whether the push went
     * through (a refused one waits for the next sync, the conflict is resolved either way). A new
     * conflict without a choice fails with [ConflictChangedException] and keeps the conflict pending.
     */
    suspend fun resolveConflicts(choices: Map<String, ConflictChoice>, remoteName: String = "origin"): Result<Boolean> =
        withContext(Dispatchers.IO) {
            if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
            val pending = pendingConflict() ?: return@withContext Result.success(true)
            gitMutex.withLock {
                cleanStaleLocksIfNeeded()
                try {
                    val provider = getCredentialsProvider() ?: return@withLock Result.failure(Exception("No API Token found"))
                    repairShallowBoundaryIfShallow()
                    Git.open(rootDir).use { git ->
                        recoverRepositoryStateIfNeeded(git)
                        git.fetch()
                            .setRemote(remoteName)
                            .setCredentialsProvider(provider)
                            .setTimeout(NETWORK_TIMEOUT_SECONDS)
                            .call()
                        val upstream = git.repository.resolve("refs/remotes/$remoteName/${git.repository.branch}")
                            ?: return@withLock Result.failure(Exception("GitHub's branch isn't known yet"))
                        SyncConflicts.resolve(
                            git = git,
                            upstream = upstream,
                            choices = choices,
                            backups = pending.backups,
                            message = RESOLUTION_MESSAGE,
                            author = PersonIdent("PixelBrain User", "user@pixelbrain.local")
                        ) { repo ->
                            val held = commitGuard.review(stagedTexts(repo))
                            if (held.isNotEmpty()) throw CommitHeldException(held)
                        }
                        SyncConflicts.clear(git.repository.directory)
                        val results = git.push()
                            .setRemote(remoteName)
                            .setCredentialsProvider(provider)
                            .setTimeout(NETWORK_TIMEOUT_SECONDS)
                            .call()
                        val rejection = describeRejectedUpdates(results)
                        if (rejection != null) Log.w("JGitProvider", "Resolution pushed later: $rejection")
                        Result.success(rejection == null)
                    }
                } catch (e: ConflictChangedException) {
                    Log.w("JGitProvider", "New conflicts while resolving: ${e.paths.size}")
                    Result.failure(e)
                } catch (e: CommitHeldException) {
                    Log.w("JGitProvider", "Resolution held by the confidentiality guard: ${e.findings.size} finding(s)")
                    Result.failure(e)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onGitFailure(e)
                    Log.e("JGitProvider", "Resolving the conflict failed", e)
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
        // A read-only device never rebases nor merges: it only fast-forwards.
        if (VaultWriteGate.readOnly) return@withContext pullFastForwardOnly(remoteName)
        // A conflict waits for the owner's choices (S1): the pull pauses, no new backup per try.
        SyncConflicts.read(File(rootDir, ".git"))?.let { return@withContext SyncResult.ConflictPending(it.paths) }

        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                ensureCriticalDirectories() // Defensive check before pulling

            val provider = getCredentialsProvider() ?: return@withContext SyncResult.Error(Exception("No API Token found"))

            repairShallowBoundaryIfShallow()
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
                            val backups = mutableListOf<String>()
                            for (relativePath in conflicts) {
                                val backup = conflictResolver.secureLocalBackup(relativePath)
                                if (backup != null) backups += backup.relativeTo(rootDir).invariantSeparatorsPath
                            }
                            val backedUpCount = backups.size
                            Log.i("JGitProvider", "$backedUpCount clean local file(s) backed up.")

                            // Remember it (S1): the next pulls pause until the owner resolves it.
                            val upstream = git.repository.resolve("refs/remotes/$remoteName/${git.repository.branch}")
                            SyncConflicts.write(
                                git.repository.directory,
                                PendingConflict(upstream?.name.orEmpty(), conflicts.distinct(), backups)
                            )

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
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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

    /**
     * A read-only device's pull: fetch, then fast-forward only. Never a rebase or a merge commit,
     * never a write of the device's own. A branch that diverged or a working tree that changed
     * stops with an error instead (see [localChanges] and [resetToRemote]).
     */
    private suspend fun pullFastForwardOnly(remoteName: String): SyncResult = gitMutex.withLock {
        cleanStaleLocksIfNeeded()
        try {
            val provider = getCredentialsProvider() ?: return@withLock SyncResult.Error(Exception("No API Token found"))
            repairShallowBoundaryIfShallow()
            Git.open(rootDir).use { git ->
                val result = git.pull()
                    .setRemote(remoteName)
                    .setRebase(false)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .setCredentialsProvider(provider)
                    .setTimeout(NETWORK_TIMEOUT_SECONDS)
                    .setProgressMonitor(AndroidLogProgressMonitor())
                    .call()
                val status = result.mergeResult?.mergeStatus
                if (result.isSuccessful && (status == null || status.isSuccessful)) {
                    Log.i("JGitProvider", "Read-only pull (fast-forward only): ${status ?: "nothing to merge"}")
                    SyncResult.Success
                } else {
                    Log.w("JGitProvider", "Read-only pull refused: $status")
                    SyncResult.Error(
                        ReadOnlyDeviceException("This read-only copy no longer follows GitHub ($status). Reset it to GitHub.")
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RefNotAdvertisedException) return@withLock SyncResult.Success
            onGitFailure(e)
            Log.e("JGitProvider", "Read-only pull failed", e)
            SyncResult.Error(e)
        }
    }

    /** The URL this clone pulls from, or null without a clone. */
    suspend fun originUrl(remoteName: String = "origin"): String? = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext null
        runCatching { Git.open(rootDir).use { it.repository.config.getString("remote", remoteName, "url") } }.getOrNull()
    }

    /**
     * What this clone holds that GitHub doesn't: changed or new files in the working tree and
     * commits not pushed yet. Always empty on a healthy read-only device (its tripwire), and what a
     * login that starts from a fresh clone would discard.
     */
    suspend fun localChanges(branch: String = "main"): LocalChanges = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext LocalChanges(emptyList(), 0)
        gitMutex.withLock {
            try {
                Git.open(rootDir).use { git ->
                    val status = git.status().call()
                    val files = (status.added + status.changed + status.modified + status.missing +
                        status.removed + status.untracked + status.conflicting).toSortedSet().toList()
                    val ahead = BranchTrackingStatus.of(git.repository, branch)?.aheadCount ?: 0
                    LocalChanges(files, ahead)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("JGitProvider", "Could not read the local changes", e)
                LocalChanges(emptyList(), 0, unreadable = true)
            }
        }
    }

    /**
     * Deletes the local clone, for a login that starts from a fresh one. It is moved aside in one
     * rename first, so a delete that fails halfway never leaves a half clone the next login opens.
     */
    suspend fun deleteLocalClone(): Boolean = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            staleLocksChecked = false
            val discarded = File(rootDir.parentFile, "vault_discarded")
            if (discarded.exists()) discarded.deleteRecursively()
            if (rootDir.exists() && !rootDir.renameTo(discarded)) return@withLock false
            discarded.deleteRecursively()
            true
        }
    }

    /**
     * A read-only device's way back to a faithful copy: fetch, hard reset to GitHub's [branch],
     * untracked files removed. Refused on a device that writes: there it could discard real work.
     */
    suspend fun resetToRemote(branch: String = "main", remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!VaultWriteGate.readOnly) return@withContext Result.failure(IllegalStateException("Only a read-only device resets to GitHub"))
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))
        gitMutex.withLock {
            cleanStaleLocksIfNeeded()
            try {
                val provider = getCredentialsProvider() ?: return@withLock Result.failure(Exception("No API Token found"))
                Git.open(rootDir).use { git ->
                    git.fetch().setRemote(remoteName).setCredentialsProvider(provider).setTimeout(NETWORK_TIMEOUT_SECONDS).call()
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("$remoteName/$branch").call()
                    git.clean().setCleanDirectories(true).call()
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
        if (VaultWriteGate.readOnly) return@withContext Result.failure(ReadOnlyDeviceException())
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

                    // Every commit goes through the confidentiality guard, this one included.
                    val held = commitGuard.review(stagedTexts(git.repository))
                    if (held.isNotEmpty()) throw CommitHeldException(held)

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
            } catch (e: CommitHeldException) {
                Log.w("GitSync", "Force push held by the confidentiality guard")
                Result.failure(e)
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

    /** The current HEAD commit id, or null (no repository, no commit yet). */
    suspend fun headId(): String? = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            if (!File(rootDir, ".git").isDirectory) return@withLock null
            try {
                Git.open(rootDir).use { it.repository.resolve("HEAD")?.name }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    /** What changed between two commits (a pull's before and after), renames paired. */
    suspend fun changesBetween(fromId: String, toId: String): List<ChangedPath> = withContext(Dispatchers.IO) {
        if (fromId == toId) return@withContext emptyList()
        gitMutex.withLock {
            try {
                Git.open(rootDir).use { git ->
                    val repo = git.repository
                    repo.newObjectReader().use { reader ->
                        fun tree(id: String) = org.eclipse.jgit.treewalk.CanonicalTreeParser().apply {
                            reset(reader, repo.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(id)).tree)
                        }
                        org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE).use { diff ->
                            diff.setRepository(repo)
                            diff.isDetectRenames = true
                            diff.scan(tree(fromId), tree(toId)).map { entry ->
                                when (entry.changeType) {
                                    org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> ChangedPath(entry.newPath, "add")
                                    org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> ChangedPath(entry.oldPath, "delete")
                                    org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> ChangedPath(entry.newPath, "modify")
                                    else -> ChangedPath(entry.newPath, "rename")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("JGitProvider", "Couldn't diff $fromId..$toId: ${e.message}")
                emptyList()
            }
        }
    }

    /** Commits since [sinceMillis] (author time), newest first, at most [max], with the paths each touched. */
    suspend fun commitsSince(sinceMillis: Long, max: Int = 500): List<CommitSummary> = withContext(Dispatchers.IO) {
        gitMutex.withLock {
            if (!File(rootDir, ".git").isDirectory) return@withLock emptyList()
            try {
                Git.open(rootDir).use { git ->
                    val repo = git.repository
                    val head = repo.resolve("HEAD") ?: return@use emptyList()
                    val boundary = shallowBoundary(repo)
                    org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
                        walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC)
                        walk.markStart(walk.parseCommit(head))
                        org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE).use { diff ->
                            diff.setRepository(repo)
                            val result = mutableListOf<CommitSummary>()
                            for (commit in walk) {
                                // A shallow clone's oldest commit has no parent here: diffed against
                                // nothing, it would show every file as changed by it.
                                if (commit in boundary) continue
                                val time = commit.authorIdent.whenAsInstant.toEpochMilli()
                                // Commit-time order: once both clocks are past the window, nothing older follows.
                                if (time < sinceMillis && commit.commitTime * 1000L < sinceMillis) break
                                if (time < sinceMillis) continue
                                val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
                                val paths = diff.scan(parent, commit.tree).map {
                                    if (it.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath
                                }
                                result += CommitSummary(commit.name, time, commit.authorIdent.name, commit.shortMessage, paths)
                                if (result.size >= max) break
                            }
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("JGitProvider", "Couldn't read recent commits: ${e.message}")
                emptyList()
            }
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
                    // A shallow clone's oldest commit holds every file without having changed it.
                    val boundary = shallowBoundary(git.repository)
                    val logs = git.log().addPath(relativePath).setMaxCount(limit).call().filterNot { it in boundary }
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

    /** Paths that differ between HEAD and the index (what the next commit will record). */
    private fun stagedPaths(repo: org.eclipse.jgit.lib.Repository): List<String> {
        val headTree = repo.resolve("HEAD^{tree}") ?: return emptyList()
        repo.newObjectReader().use { reader ->
            val committed = org.eclipse.jgit.treewalk.CanonicalTreeParser().apply { reset(reader, headTree) }
            val staged = org.eclipse.jgit.dircache.DirCacheIterator(repo.readDirCache())
            org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE).use { diff ->
                diff.setRepository(repo)
                return diff.scan(committed, staged).map {
                    if (it.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath
                }
            }
        }
    }

    /**
     * What the next commit would record, as text, for the confidentiality guard: index vs HEAD (or
     * vs nothing in a repository without commits), renames paired with their source. Deletions add
     * nothing and are skipped. Binary, oversized and encrypted content is left out ([StagedText.newText]
     * null): only its path is checked.
     */
    private fun stagedTexts(repo: org.eclipse.jgit.lib.Repository): List<StagedText> {
        val headTree = repo.resolve("HEAD^{tree}")
        repo.newObjectReader().use { reader ->
            val committed = if (headTree != null) {
                org.eclipse.jgit.treewalk.CanonicalTreeParser().apply { reset(reader, headTree) }
            } else {
                org.eclipse.jgit.treewalk.EmptyTreeIterator()
            }
            val staged = org.eclipse.jgit.dircache.DirCacheIterator(repo.readDirCache())
            org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE).use { diff ->
                diff.setRepository(repo)
                diff.isDetectRenames = true
                return diff.scan(committed, staged).mapNotNull { entry ->
                    when (entry.changeType) {
                        org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> null
                        org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> StagedText(
                            path = entry.newPath,
                            oldPath = null,
                            oldText = null,
                            newText = blobText(reader, entry.newPath, entry.newId),
                            isNewPath = true
                        )
                        org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> StagedText(
                            path = entry.newPath,
                            oldPath = null,
                            oldText = blobText(reader, entry.oldPath, entry.oldId),
                            newText = blobText(reader, entry.newPath, entry.newId),
                            isNewPath = false
                        )
                        else -> StagedText( // RENAME, COPY
                            path = entry.newPath,
                            oldPath = entry.oldPath,
                            oldText = blobText(reader, entry.oldPath, entry.oldId),
                            newText = blobText(reader, entry.newPath, entry.newId),
                            isNewPath = true
                        )
                    }
                }
            }
        }
    }

    /** A blob as UTF-8 text, or null when it can't be checked as text (encrypted, binary, oversized). */
    private fun blobText(
        reader: org.eclipse.jgit.lib.ObjectReader,
        path: String,
        id: org.eclipse.jgit.lib.AbbreviatedObjectId
    ): String? {
        if (path.endsWith(".enc")) return null
        val objectId = id.toObjectId() ?: return null
        val loader = reader.open(objectId, org.eclipse.jgit.lib.Constants.OBJ_BLOB)
        if (loader.size > GUARD_MAX_TEXT_BYTES) {
            Log.w("JGitProvider", "Guard: content too large to check, path only")
            return null
        }
        val bytes = loader.bytes
        if (org.eclipse.jgit.diff.RawText.isBinary(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        /** Notes are small; larger text (exports, dumps) is left to the path check. */
        const val GUARD_MAX_TEXT_BYTES = 4L * 1024 * 1024

        /** Used when a commit message would name a protected term. */
        const val NEUTRAL_COMMIT_MESSAGE = "vault: update notes"
        /** The one commit a resolved sync conflict lands as (S1). */
        const val RESOLUTION_MESSAGE = "vault: resolve a sync conflict"

        /**
         * Socket inactivity timeout for clone/pull/push. JGit defaults to 0 = none, and Android's
         * HttpURLConnection is also unbounded, so one stalled connection (network handover, Wi-Fi
         * dropping on resume) used to hang the operation forever while holding the git locks —
         * blocking every later save and leaving the sync indicator spinning.
         */
        const val NETWORK_TIMEOUT_SECONDS = 30

        /** First-clone attempts on a dropped connection, [RETRY_BACKOFF_MS] × attempt² apart (5 s, then 20 s). */
        const val CLONE_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 5_000L

        /** Commits per history step after a shallow clone: one short transfer each. */
        const val HISTORY_STEP = 1_000

    }
}

/** What a clone holds that GitHub doesn't (see [JGitProvider.localChanges]). */
data class LocalChanges(val files: List<String>, val unpushedCommits: Int, val unreadable: Boolean = false) {
    val isEmpty: Boolean get() = files.isEmpty() && unpushedCommits == 0 && !unreadable
}
