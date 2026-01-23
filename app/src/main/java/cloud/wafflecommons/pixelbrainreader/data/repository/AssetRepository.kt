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
    @ApplicationContext private val context: Context,
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

    fun getAssetFile(path: String): File? {
        val file = File(rootDir, path)
        return if (file.exists()) file else null
    }
}
