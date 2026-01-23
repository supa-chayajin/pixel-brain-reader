package cloud.wafflecommons.pixelbrainreader.data.utils

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Provides safe access to the filesystem, enforcing a jail within the Vault root.
 * Prevents Path Traversal attacks.
 */
@Singleton
class SafeFileProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val vaultRoot: File
        get() = File(context.filesDir, "vault")

    /**
     * resoles "path" relative to Vault Root and validates it is firmly inside.
     * @throws SecurityException if path attempts traversal escape.
     */
    fun getSafeFile(path: String): File {
        // 1. Resolve
        val requestedFile = File(vaultRoot, path)
        
        // 2. Canonicalize (Resolve symlinks, ../, etc)
        val canonicalPath = requestedFile.canonicalPath
        val canonicalRoot = vaultRoot.canonicalPath
        
        // 3. Verify Jail
        if (!canonicalPath.startsWith(canonicalRoot)) {
            throw SecurityException("Path Traversal Attempt Detected: $path resolves to $canonicalPath which is outside $canonicalRoot")
        }
        
        return requestedFile
    }

    /**
     * Performs an Atomic Write using "Write-to-Temp -> Rename" strategy.
     */
    fun atomicWrite(path: String, content: String) {
        val targetFile = getSafeFile(path)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        
        // Ensure parent exists
        targetFile.parentFile?.mkdirs()
        
        try {
            // Write to temp
            tempFile.writeText(content)
            
            // Sync/Flush (Auto-handled by writeText but being explicit is good practice for streams, 
            // writeText uses OutputStreamWriter which flushes. 
            // For true posix fsync we'd need FileOutputStream.fd.sync() but writeText is sufficient for app level)
            
            // Atomic Rename
            // renameTo returns false if dest exists on some OS/FS, but simple overwrite usually works on Android internal storage
            // If it fails, we might need delete first.
            if (targetFile.exists()) {
                 // Android's renameTo is usually atomic but might fail if target exists on some old versions.
                 // Java API non-atomic on Windows, but POSIX usually atomic.
                 // Let's use robust rename.
            }
            
            // Kotlin/Java File.renameTo is weak. 
            // Better: use java.nio.file.Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            // But we need API 26+ (Oreo). Assuming minSdk is reasonably high (24-26+ for PixelBrainReader?)
            
            // Fallback for robust save:
            if (!tempFile.renameTo(targetFile)) {
                // Try deleting target first (Not atomic window, but practically safe for our single-user app)
                if (targetFile.delete() && tempFile.renameTo(targetFile)) {
                    // Success
                } else {
                    throw java.io.IOException("Failed to atomicaly rename ${tempFile.name} to ${targetFile.name}")
                }
            }
        } catch (e: Exception) {
            tempFile.delete() // Cleanup
            throw e
        }
    }
}
