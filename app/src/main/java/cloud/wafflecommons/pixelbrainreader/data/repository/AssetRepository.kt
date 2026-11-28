package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles binary assets (Images, PDFs). Since 13.4 also a file at a chosen vault path (a game's own
 * picture, P5): written atomically through [SafeFileProvider], deleted, and found again, each behind
 * the read-only gate and inside the vault.
 */
@Singleton
class AssetRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val jGitProvider: JGitProvider,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val safeFileProvider: SafeFileProvider
) {
    private val rootDir: File
        get() = File(context.filesDir, "vault")

    suspend fun saveAsset(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        VaultWriteGate.check()
        val file = File(rootDir, path)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        
        vaultDiscoveryRepository.scanSingleFile(path)
        jGitProvider.addAll()
    }

    /**
     * Stores a file dropped or shared into a note (E6) in the vault's attachment folder — read
     * from its Obsidian settings — under a name that can't collide, atomically, and returns its
     * vault path; null when [open] gives nothing.
     */
    suspend fun saveDropped(notePath: String, mime: String, originalName: String?, open: () -> java.io.InputStream?): String? =
        withContext(Dispatchers.IO) {
            VaultWriteGate.check()
            val setting = cloud.wafflecommons.pixelbrainreader.data.vault.Attachments.settingOf(
                File(rootDir, ".obsidian/app.json").takeIf { it.exists() }?.readText()
            )
            val folder = cloud.wafflecommons.pixelbrainreader.data.vault.Attachments.folder(setting, notePath)
            val name = cloud.wafflecommons.pixelbrainreader.data.vault.Attachments.fileName(java.time.LocalDateTime.now(), mime, originalName)
            var path = listOf(folder, name).filter { it.isNotEmpty() }.joinToString("/")
            var n = 2
            while (File(rootDir, path).exists()) {
                path = path.substringBeforeLast('.') + " $n." + path.substringAfterLast('.')
                n++
            }
            val staging = File(context.filesDir, "vault_staging").apply { mkdirs() }
            val temp = File(staging, "drop-${System.nanoTime()}.tmp")
            try {
                val input = open() ?: return@withContext null
                input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
                val target = File(rootDir, path).apply { parentFile?.mkdirs() }
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (temp.exists()) temp.delete()
            }
            vaultDiscoveryRepository.scanSingleFile(path)
            path
        }

    fun getAssetFile(path: String): File? {
        val file = File(rootDir, path)
        return if (file.exists()) file else null
    }

    /** Writes [bytes] at the vault path [path] atomically (staged outside the vault, fsynced, renamed), into the file index. False on failure; refused on a read-only device. */
    suspend fun writeAt(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            VaultWriteGate.check()
            safeFileProvider.atomicWrite(path, bytes)
            vaultDiscoveryRepository.scanSingleFile(path)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the vault file [path] (never a folder) and drops it from the file index; false when absent. Refused on a read-only device. */
    suspend fun deleteAt(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            VaultWriteGate.check()
            val file = safeFileProvider.getSafeFile(path)
            if (!file.isFile || !file.delete()) return@withContext false
            vaultDiscoveryRepository.scanSingleFile(path)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** The existing vault file at [path], its canonical path inside the vault; null otherwise. Blocking: call from IO. */
    fun safeFile(path: String): File? = try {
        safeFileProvider.getSafeFile(path).canonicalFile.takeIf { it.isFile }
    } catch (e: SecurityException) {
        null
    } catch (e: java.io.IOException) {
        null
    }
}
