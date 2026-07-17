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
     *
     * Uses java.nio ATOMIC_MOVE (minSdk 36, always available) which is a POSIX rename() —
     * it replaces the target in a single atomic step. The old delete()-then-rename() path
     * had a crash window where the target was already gone but the rename hadn't landed,
     * which could lose the file entirely.
     */
    fun atomicWrite(path: String, content: String) {
        val targetFile = getSafeFile(path)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        // Ensure parent exists
        targetFile.parentFile?.mkdirs()

        try {
            tempFile.writeText(content)
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
                // Practically never happens on app-internal storage; degrade to a
                // (non-atomic) replace rather than failing the save outright.
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            tempFile.delete() // Cleanup
            throw e
        }
    }
}
