package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles binary assets (Images, PDFs).
 */
@Singleton
class AssetRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val jGitProvider: JGitProvider,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository
) {
    private val rootDir: File
        get() = File(context.filesDir, "vault")

    suspend fun saveAsset(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
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
}
