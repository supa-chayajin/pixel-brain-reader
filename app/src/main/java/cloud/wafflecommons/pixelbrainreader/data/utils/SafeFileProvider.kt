package cloud.wafflecommons.pixelbrainreader.data.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides safe access to the filesystem, enforcing a jail within the Vault root.
 * Prevents Path Traversal attacks.
 */
@Singleton
class SafeFileProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val vaultRoot: File
        get() = File(context.filesDir, "vault")

    /**
     * Where writes are staged: beside the vault, not inside it, so a git commit or a vault scan never
     * picks up a half-written temp file — and on the same filesystem, so the final rename stays atomic.
     */
    private val stagingDir: File
        get() = File(context.filesDir, STAGING_DIR).apply { mkdirs() }

    /**
     * Resolves [path] relative to the Vault root and validates it stays inside it.
     * @throws SecurityException if the path escapes the vault.
     */
    fun getSafeFile(path: String): File {
        val requestedFile = File(vaultRoot, path)

        // Canonicalize (resolves symlinks, ../, etc.)
        val canonicalPath = requestedFile.canonicalPath
        val canonicalRoot = vaultRoot.canonicalPath

        // Compare against the root PLUS a separator: a bare startsWith(root) also accepted sibling
        // directories sharing the prefix (e.g. "../vault_backup/x" → ".../vault_backup/x").
        if (canonicalPath != canonicalRoot && !canonicalPath.startsWith(canonicalRoot + File.separator)) {
            throw SecurityException("Path Traversal Attempt Detected: $path resolves outside the vault")
        }

        return requestedFile
    }

    /** UTF-8 text variant of [atomicWrite]. */
    fun atomicWrite(path: String, content: String) = atomicWrite(path, content.toByteArray(Charsets.UTF_8))

    /**
     * Atomic, durable write: write a temp file, fsync it, then ATOMIC_MOVE it over the target (a
     * POSIX rename). A crash or power loss leaves either the old or the new file — never a truncated
     * one (the fsync matters: without it the rename can land before the data does).
     */
    fun atomicWrite(path: String, bytes: ByteArray) {
        val targetFile = getSafeFile(path)
        targetFile.parentFile?.mkdirs()
        // One temp file per write: concurrent saves of the same note (typing autosave + pause flush)
        // used to share "<name>.tmp", so one of them could publish the other's half-written bytes.
        val tempFile = File.createTempFile("write-", ".tmp", stagingDir)

        try {
            FileOutputStream(tempFile).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (atomicUnsupported: AtomicMoveNotSupportedException) {
                // Practically never happens on app-internal storage; degrade to a
                // (non-atomic) replace rather than failing the save outright.
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete() // Already gone once moved; cleans up after a failure.
        }
    }

    private companion object {
        const val STAGING_DIR = "vault_staging"
    }
}
