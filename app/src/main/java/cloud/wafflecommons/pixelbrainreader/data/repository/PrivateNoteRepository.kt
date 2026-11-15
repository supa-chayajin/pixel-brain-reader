package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultKey
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivateNoteRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val pushScheduler: VaultPushScheduler,
    private val session: PrivateVaultSession
) {

    private val vaultDir: File
        get() = File(context.filesDir, "vault/99_Private").apply {
            if (!exists()) mkdirs()
        }

    /** Encrypted writes are staged here, OUTSIDE the vault, so a git commit never sees a half-written file. */
    private val stagingDir: File
        get() = File(context.filesDir, "private_staging").apply {
            if (!exists()) mkdirs()
        }

    /**
     * returns a list of encrypted files.
     */
    suspend fun getPrivateNotes(): List<File> = withContext(Dispatchers.IO) {
        vaultDir.listFiles { file ->
            file.isFile && file.name.endsWith(".md.enc", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Encrypts and saves a note, then queues a batched background push.
     *
     * The ciphertext is written atomically: a crash mid-write used to leave a truncated file that no
     * longer authenticates — the whole note lost. And each save (the editor autosaves after every
     * typing pause) used to run its own commit + network push inline. It runs under the session's
     * write lock with [key], taken when the save was asked for: a save asked just before a lock lands.
     */
    suspend fun createNote(filename: String, content: String, key: PrivateVaultKey) = withContext(Dispatchers.IO) {
        // A title is not a path: separators would write outside the private folder.
        val baseName = filename.replace('/', '-').replace('\\', '-')
        val safeName = if (baseName.endsWith(".md.enc")) baseName else "$baseName.md.enc"

        try {
            session.writeWith(key) { password ->
                val encryptedBytes = cryptoManager.encrypt(content, password)
                writeAtomically(File(vaultDir, safeName), encryptedBytes)
            }
            // No filename/path/size logging — private files leak identifying
            // info through any of those (the filename IS the note title).
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PrivateRepo", "Failed to write private note")
            throw e // Propagate write failures to ViewModel
        }
        pushScheduler.requestPush()
    }

    /** The same with a password typed and verified for this one write (the confidentiality guard's "move to private"). */
    suspend fun createNote(filename: String, content: String, password: CharArray) {
        val key = PrivateVaultKey(password.copyOf())
        try {
            createNote(filename, content, key)
        } finally {
            key.wipe()
        }
    }

    /**
     * Reads and decrypts a note.
     */
    suspend fun readNote(file: File, key: PrivateVaultKey): String = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IOException("File not found")

        try {
            val fileBytes = file.readBytes()
            return@withContext cryptoManager.decrypt(fileBytes, key.password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Failed to decrypt note: possibly wrong password", e)
        }
    }

    /**
     * True when [password] opens the vault: it decrypts one of the two newest encrypted notes
     * anywhere under `99_Private/` (the journal, People, an encrypted mission log). An empty vault
     * accepts any password (it becomes the vault's password). Two notes are tried, so one damaged
     * file can't lock the user out. Only the journal's own notes used to be tried: with an empty
     * journal, a typo was accepted while People notes sat under the real password.
     */
    suspend fun verifyPassword(password: CharArray): Boolean = withContext(Dispatchers.IO) {
        val candidates = vaultDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md.enc", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .take(PASSWORD_CHECK_SAMPLE)
            .toList()
        if (candidates.isEmpty()) return@withContext true
        candidates.any { file ->
            try {
                cryptoManager.decrypt(file.readBytes(), password)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun deleteNote(file: File) = withContext(Dispatchers.IO) {
        VaultWriteGate.check()
        if (file.exists() && file.delete()) pushScheduler.requestPush()
    }

    /** Drops the cached encryption keys (the vault was locked). */
    fun forgetKeys() {
        cryptoManager.clearKeyCache()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        VaultWriteGate.check()
        val temp = File(stagingDir, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        const val PASSWORD_CHECK_SAMPLE = 2
    }
}
