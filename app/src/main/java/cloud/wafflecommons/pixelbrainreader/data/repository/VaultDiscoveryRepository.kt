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

        // 1. Get DB State
        val dbFilesMap = fileDao.getAllFileShas().associate { it.path to it }
        val dbPaths = dbFilesMap.keys

        // 2. Walk FS
        val fsPaths = mutableSetOf<String>()
        val changedFiles = mutableListOf<FileEntity>()
        val newFiles = mutableListOf<FileEntity>() // To be inserted into DB

        rootDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == ".git") return@forEach
            
            val relativePath = file.relativeTo(rootDir).path
            if (relativePath.isEmpty()) return@forEach
            
            fsPaths.add(relativePath)
            
            val lastMod = file.lastModified()
            val isModified = lastMod > sinceTimestamp
            
            if (!dbPaths.contains(relativePath)) {
                // NEW FILE (Automatically "Modified" compared to 0 or missing)
                val entity = createFileEntity(file, relativePath)
                newFiles.add(entity)
                changedFiles.add(entity)
            } else {
                // EXISTING
                if (isModified) {
                    // Update Metadata in DB + Mark for Embedding
                    val entity = createFileEntity(file, relativePath)
                    newFiles.add(entity) // Will Replace in DB
                    changedFiles.add(entity)
                }
            }
        }

        // 3. Batch Updates to DB
        if (newFiles.isNotEmpty()) {
            fileDao.insertAll(newFiles)
            Log.i("VaultDiscovery", "Indexed ${newFiles.size} new/modified items.")
        }
        
        val toDelete = dbPaths.minus(fsPaths)
        if (toDelete.isNotEmpty()) {
            fileDao.deleteFiles(toDelete.toList())
            Log.i("VaultDiscovery", "Pruned ${toDelete.size} deleted items.")
        }
        
        Log.d("VaultDiscovery", "Scan took ${System.currentTimeMillis() - start}ms. Found ${changedFiles.size} changed items since $sinceTimestamp")
        
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
         
         if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
             try {
                 // Metadata Extraction
                 // Limit read to first 2KB for performance
                 val content = file.reader().use { it.readText() } // read full for robust regex? Or partial.
                 // Kaml FrontmatterManager expects full content to parse safely usually.
                 // Let's read full, files are small.
                 
                 val metadata = FrontmatterManager.extractMetadata(content)
                 if (metadata.tags.isNotEmpty()) {
                     tags = metadata.tags.joinToString(",")
                 }
                 // Store other useful things? mood?
                 // Create a mini-JSON for rawMetadata using Gson map
                 val extra = mutableMapOf<String, Any>()
                 if (metadata.aliases.isNotEmpty()) extra["aliases"] = metadata.aliases
                 // We could store mood here too if we want file list to show it?
                 // For now, tags are the requirement.
                 
                 if (extra.isNotEmpty()) {
                     metaBlob = gson.toJson(extra)
                 }
             } catch (e: Exception) {
                 // Log.w("VaultDiscovery", "Failed to extract metadata for $path")
             }
         }
         
         return FileEntity(
            path = path,
            name = file.name,
            type = if (file.isDirectory) "dir" else "file",
            downloadUrl = null,
            isDirty = true,
            localModifiedTimestamp = file.lastModified(),
            tags = tags,
            rawMetadata = metaBlob
        )
    }
}
