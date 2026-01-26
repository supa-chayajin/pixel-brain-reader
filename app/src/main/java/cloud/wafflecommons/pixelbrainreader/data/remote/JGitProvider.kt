package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-First Git Provider using Eclipse JGit.
 * Operates directly on the device filesystem.
 */
@Singleton
class JGitProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretManager: SecretManager
) {

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /**
     * Set up the repository.
     * Strategies:
     * 1. If repo exists -> Open it.
     * 2. If repo missing & URL provided -> Clone it (First Sync).
     * 3. If repo missing & No URL -> Init new local repo.
     */
    suspend fun setupRepository(remoteUrl: String?, branch: String = "main"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isReady()) {
                // Already initialized
                return@withContext Result.success(Unit)
            }

            if (!remoteUrl.isNullOrEmpty()) {
                // CLONE STRATEGY
                Log.i("JGitProvider", "Starting fresh clone from $remoteUrl branch: $branch")
                val token = secretManager.getToken() ?: throw Exception("Clone requires API Token")
                val provider = UsernamePasswordCredentialsProvider("token", token)

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
            Result.failure(e)
        }
    }

    // Keep initRepository for backward compatibility/local-init fallback
    suspend fun initRepository(): Result<Unit> = setupRepository(null)

    /**
     * Stages all changes (git add .).
     */
    suspend fun addAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Git.open(rootDir).use { git ->
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Commits staged changes.
     */
    suspend fun commit(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Git.open(rootDir).use { git ->
                val status = git.status().call()
                if (status.hasUncommittedChanges()) {
                    git.commit()
                        .setMessage(message)
                        .setAuthor("PixelBrain User", "user@pixelbrain.local")
                        .call()
                    Log.i("JGitProvider", "Committed: $message")
                } else {
                    Log.d("JGitProvider", "No changes to commit.")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("JGitProvider", "Commit Failed", e)
            Result.failure(e)
        }
    }

    /**
     * Pushes to remote.
     */
    suspend fun push(remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))
        
        try {
            val token = secretManager.getToken() ?: return@withContext Result.failure(Exception("No API Token found"))
            val provider = UsernamePasswordCredentialsProvider("token", token)

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
            Log.e("JGitProvider", "Push Failed", e)
            Result.failure(e)
        }
    }

    /**
     * Pulls from remote (Rebase).
     */
    suspend fun pull(remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repository not initialized"))

        try {
            val token = secretManager.getToken() ?: return@withContext Result.failure(Exception("No API Token found"))
            val provider = UsernamePasswordCredentialsProvider("token", token)

            Git.open(rootDir).use { git ->
                git.pull()
                    .setRemote(remoteName)
                    .setRebase(true) // Rebase Strategy
                    .setCredentialsProvider(provider)
                    .setProgressMonitor(AndroidLogProgressMonitor())
                    .call()
            }
            Log.i("JGitProvider", "Pull Successful")
            Result.success(Unit)
        } catch (e: Exception) {
             if (e is RefNotAdvertisedException) {
                 Log.w("JGitProvider", "Pull skipped: Ref not advertised (Empty repo?)")
                 Result.success(Unit)
             } else {
                 Log.e("JGitProvider", "Pull Failed", e)
                 Result.failure(e)
             }
        }
    }

    /**
     * Configures the remote URL.
     */
    suspend fun setRemote(url: String, remoteName: String = "origin"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(Exception("Repo not ready"))
        try {
            Git.open(rootDir).use { git ->
                val config = git.repository.config
                config.setString("remote", remoteName, "url", url)
                config.save()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
        
        try {
            val token = secretManager.getToken() ?: return@withContext Result.failure(Exception("No API Token found"))
            val provider = UsernamePasswordCredentialsProvider("token", token)

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
            Log.e("GitSync", "Force Push Failed", e)
            Result.failure(e)
        }
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
