package cloud.wafflecommons.pixelbrainreader.data.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ConflictResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /**
     * Secures a backup of the local file before JGit overwrites it.
     */
    suspend fun secureLocalBackup(conflictingRelativePath: String): File? = withContext(Dispatchers.IO) {
        try {
            val originalFile = File(rootDir, conflictingRelativePath)
            if (!originalFile.exists() || !originalFile.canRead()) {
                Log.w("ConflictResolver", "Original file does not exist or is unreadable: $conflictingRelativePath")
                return@withContext null
            }

            val baseName = originalFile.nameWithoutExtension
            val extension = originalFile.extension.let { if (it.isNotEmpty()) ".$it" else "" }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFileName = "${baseName}_sync_conflict_${timestamp}${extension}"
            
            val backupFile = File(originalFile.parentFile, backupFileName)
            
            originalFile.copyTo(backupFile, overwrite = true)
            Log.i("ConflictResolver", "Successfully backed up conflicting file to: ${backupFile.name}")
            
            return@withContext backupFile
        } catch (e: Exception) {
            Log.e("ConflictResolver", "Failed to secure local backup for: $conflictingRelativePath", e)
            return@withContext null
        }
    }
}
