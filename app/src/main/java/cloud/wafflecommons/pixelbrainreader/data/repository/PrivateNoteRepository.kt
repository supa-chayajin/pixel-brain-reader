package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivateNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val gitProvider: JGitProvider,
    private val secretManager: SecretManager
) {

    private val vaultDir: File
        get() = File(context.filesDir, "vault/99_Private").apply {
             if (!exists()) mkdirs()
        }

    /**
     * returns a list of encrypted files.
     */
    suspend fun getPrivateNotes(): List<File> = withContext(Dispatchers.IO) {
        // Ensure dir exists before listing
        if (!vaultDir.exists()) {
             vaultDir.mkdirs()
        }
        vaultDir.listFiles { file ->
            file.isFile && file.name.endsWith(".md.enc", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    /**
     * Encrypts and saves content to disk, then triggers Auto-Sync.
     */
    suspend fun createNote(filename: String, content: String, password: CharArray) = withContext(Dispatchers.IO) {
        val safeName = if (filename.endsWith(".md.enc")) filename else "$filename.md.enc"
        
        // 1. Force Directory Creation & Log
        if (!vaultDir.exists()) {
             val created = vaultDir.mkdirs()
             Log.d("PrivateRepo", "Creating vault dir: ${vaultDir.absolutePath} -> Success: $created")
        }

        // 2. Encrypt & Write
        try {
            val encryptedBytes = cryptoManager.encrypt(content, password)
            val file = File(vaultDir, safeName)
            
            FileOutputStream(file).use { fos ->
                fos.write(encryptedBytes)
            }
            Log.i("PrivateRepo", "File written successfully: ${file.absolutePath}, Size: ${file.length()}")

            // 3. Auto-Sync (Refactored for robustness)
            try {
                val message = "Secure: Update Private Vault ($safeName)"

                // 1. Commit (Locally)
                gitProvider.commit(message) // Now handles staging & detached head

                // 2. Push (Remote)
                val token = secretManager.getToken()
                if (!token.isNullOrBlank()) {
                    gitProvider.push() // Uses internal token handling, defaulting to "origin"
                    Log.i("PrivateRepo", "Private note pushed successfully.")
                } else {
                    Log.w("PrivateRepo", "Cannot push: No auth token.")
                }
            } catch (e: Exception) {
                Log.e("PrivateRepo", "Sync failed after save (File is safe locally)", e)
                // Do NOT re-throw. Local save success is what matters to the UI.
            }

        } catch (e: Exception) {
            Log.e("PrivateRepo", "Failed to write private note", e)
            throw e // Propagate write failures to ViewModel
        }
    }

    /**
     * Reads and decrypts a note.
     */
    suspend fun readNote(file: File, password: CharArray): String = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IOException("File not found")
        
        try {
            val fileBytes = file.readBytes()
            return@withContext cryptoManager.decrypt(fileBytes, password)
        } catch (e: Exception) {
            // Re-throw as a standardized SecurityException or similar if needed, 
            // but for now generic Exception is handled by UI.
            throw SecurityException("Failed to decrypt note: possibly wrong password", e)
        }
    }
    
    suspend fun deleteNote(file: File) = withContext(Dispatchers.IO) {
        if (file.exists()) {
            file.delete()
        }
    }
}
