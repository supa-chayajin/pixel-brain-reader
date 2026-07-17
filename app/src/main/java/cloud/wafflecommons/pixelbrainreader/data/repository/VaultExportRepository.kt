package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports the on-device vault (which lives in app-private storage at
 * `filesDir/vault`, inaccessible to other apps) to a user-chosen location as a
 * single ZIP, via the Storage Access Framework. This is the only path that lets the
 * user get their Markdown out of the app — everything else syncs to git or stays
 * internal.
 *
 * Excludes git/Obsidian app-state directories and the encrypted private notes
 * (`*.md.enc`), which must never leave the device in plaintext-adjacent form.
 */
@Singleton
class VaultExportRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /**
     * Zip the vault into [outputUri] (a document created via SAF `CreateDocument`).
     * Reports progress as (filesWritten, totalFiles). Returns the number of files written,
     * or a failure the caller can surface to the UI.
     */
    suspend fun exportVaultZip(
        outputUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!rootDir.exists()) {
                return@withContext Result.failure(IllegalStateException("Vault not found."))
            }

            val files = rootDir.walkTopDown()
                .onEnter { it.name !in EXCLUDED_DIRS }
                .filter { it.isFile && !it.name.endsWith(".md.enc") }
                .toList()

            if (files.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No files to export."))
            }

            val stream = context.contentResolver.openOutputStream(outputUri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to open destination."))

            stream.use { os ->
                ZipOutputStream(BufferedOutputStream(os)).use { zip ->
                    files.forEachIndexed { index, file ->
                        // Preserve the vault's folder structure inside the archive.
                        val entryName = file.relativeTo(rootDir).path
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        onProgress(index + 1, files.size)
                    }
                }
            }
            Log.i("VaultExport", "Exported ${files.size} files to $outputUri")
            Result.success(files.size)
        } catch (e: Exception) {
            Log.e("VaultExport", "Vault export failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        /** App-state directories never included in a user-facing export. */
        val EXCLUDED_DIRS = setOf(".git", ".obsidian", ".trash", ".devtool", ".idea", ".opencode")
    }
}
