package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsible for File System Discovery and Indexing (SQLite).
 * Scans the vault and keeps the DB in sync.
 */
@Singleton
class VaultDiscoveryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val gson: Gson // For JSON serialization of simplified metadata
) {

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    fun getAllFilesFlow(path: String?): Flow<List<FileEntity>> {
        // If path is null/empty, we might return all or root. 
        // Existing DAO getFiles(path) usually filters by parent folder.
        return fileDao.getFiles(path ?: "")
    }
    
    fun searchFiles(query: String): Flow<List<FileEntity>> {
        return fileDao.searchFiles(query)
    }

    suspend fun reindexAll(sinceTimestamp: Long = 0L): List<FileEntity> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        if (!rootDir.exists()) rootDir.mkdirs()

        // 1. Snapshot existing FileEntity rows (path + sha + mtime).
        val dbFilesMap = fileDao.getAllFileFingerprints().associateBy { it.path }
        val dbPaths = dbFilesMap.keys

        // 2. Walk FS
        val fsPaths = mutableSetOf<String>()
        val changedFiles = mutableListOf<FileEntity>()
        val newFiles = mutableListOf<FileEntity>()       // REPLACE-inserted (triggers FK CASCADE)
        val mtimeRefreshes = mutableListOf<Pair<String, Long>>() // UPDATE-only (no CASCADE)

        // Prune at descent time, NOT inside the loop. `walkTopDown().forEach`
        // with a name check is `continue`, not `prune` — by the time we react,
        // the walker has already queued every child of `.git`. The result is
        // that `.git/refs/heads`, `.git/objects/...`, etc. get treated as
        // vault files. Using `.onEnter` keeps the entire subtree out of the
        // scan in the first place. Mirrors Obsidian's own "what is a note"
        // convention — anything that's app/runtime state, not user content.
        rootDir.walkTopDown().onEnter { dir ->
            dir.name !in EXCLUDED_DIRS
        }.forEach { file ->
            val relativePath = file.relativeTo(rootDir).path
            if (relativePath.isEmpty()) return@forEach

            fsPaths.add(relativePath)

            val dbEntity = dbFilesMap[relativePath]
            val onDiskMtime = file.lastModified()

            if (dbEntity == null) {
                // NEW FILE — must compute fingerprint and insert.
                val entity = createFileEntity(file, relativePath)
                newFiles.add(entity)
                changedFiles.add(entity)
                return@forEach
            }

            // EXISTING FILE — fast path: mtime unchanged ⇒ content unchanged.
            // Skip without recomputing the SHA. This is the steady-state path.
            if (dbEntity.localModifiedTimestamp == onDiskMtime && dbEntity.sha != null) {
                return@forEach
            }

            // mtime drifted (or sha was null from a legacy row).
            // Compute a content fingerprint and compare.
            val newEntity = createFileEntity(file, relativePath)
            if (newEntity.sha != null && newEntity.sha == dbEntity.sha) {
                // Content identical — bump stored mtime via UPDATE so we hit the
                // fast path next time. Critically, this is NOT a REPLACE, so the
                // FK CASCADE on EmbeddingEntity does not fire and the existing
                // embeddings for this file are preserved.
                mtimeRefreshes.add(relativePath to onDiskMtime)
                return@forEach
            }

            // Content actually changed — REPLACE-insert. This will (correctly)
            // cascade-delete this file's old embeddings; IndexingWorker will
            // re-embed it on its next pass.
            newFiles.add(newEntity)
            changedFiles.add(newEntity)
        }

        // 3. Batch Updates to DB
        if (newFiles.isNotEmpty()) {
            fileDao.insertAll(newFiles)
            Log.i("VaultDiscovery", "Indexed ${newFiles.size} new/modified items.")
        }
        if (mtimeRefreshes.isNotEmpty()) {
            mtimeRefreshes.forEach { (path, mtime) -> fileDao.updateMtime(path, mtime) }
            Log.d("VaultDiscovery", "Refreshed mtime for ${mtimeRefreshes.size} unchanged item(s).")
        }

        val toDelete = dbPaths.minus(fsPaths)
        if (toDelete.isNotEmpty()) {
            fileDao.deleteFiles(toDelete.toList())
            Log.i("VaultDiscovery", "Pruned ${toDelete.size} deleted items.")
        }

        Log.d(
            "VaultDiscovery",
            "Scan took ${System.currentTimeMillis() - start}ms. Found ${changedFiles.size} actually-changed item(s); " +
                "${mtimeRefreshes.size} mtime-only drift(s) absorbed."
        )

        return@withContext changedFiles
    }
    
    suspend fun reindexFileSystem() {
         // Legacy overload
         reindexAll(0L)
    }
    
    suspend fun scanSingleFile(path: String) = withContext(Dispatchers.IO) {
        // Lightweight update for single file
        val file = File(rootDir, path)
        if (file.exists()) {
             val entity = createFileEntity(file, path)
             fileDao.insertFile(entity) // Insert or Update
        } else {
            fileDao.deleteFiles(listOf(path))
        }
    }
    
    private fun createFileEntity(file: File, path: String): FileEntity {
         var tags: String? = null
         var metaBlob: String? = null
         var contentSha: String? = null

         if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
             try {
                 val content = file.reader().use { it.readText() }

                 val metadata = FrontmatterManager.extractMetadata(content)
                 if (metadata.tags.isNotEmpty()) {
                     tags = metadata.tags.joinToString(",")
                 }
                 val extra = mutableMapOf<String, Any>()
                 if (metadata.aliases.isNotEmpty()) extra["aliases"] = metadata.aliases
                 if (extra.isNotEmpty()) {
                     metaBlob = gson.toJson(extra)
                 }

                 contentSha = sha1Of(content.toByteArray(Charsets.UTF_8))
             } catch (e: Exception) {
                 // Log.w("VaultDiscovery", "Failed to extract metadata for $path")
             }
         } else if (file.isFile) {
             // Binary or encrypted file (e.g. .md.enc). Hash the raw bytes so
             // delta-scan can detect real changes without inspecting plaintext.
             try {
                 contentSha = sha1Of(file.readBytes())
             } catch (e: Exception) {
                 // Leave SHA null; the reindex falls back to mtime comparison.
             }
         }

         return FileEntity(
            path = path,
            name = file.name,
            type = if (file.isDirectory) "dir" else "file",
            sha = contentSha,
            downloadUrl = null,
            isDirty = true,
            localModifiedTimestamp = file.lastModified(),
            tags = tags,
            rawMetadata = metaBlob
        )
    }

    private companion object {
        /** Directories never indexed: git internals + Obsidian app state. */
        val EXCLUDED_DIRS = setOf(
            ".git",
            ".obsidian",
            ".trash",
            ".devtool",
            ".idea",
            ".opencode",
        )
    }

    private fun sha1Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { b -> sb.append(String.format("%02x", b)) }
        return sb.toString()
    }
}
