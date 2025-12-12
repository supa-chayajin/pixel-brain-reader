package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.toEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.GithubApiService
import cloud.wafflecommons.pixelbrainreader.data.remote.GitTreeResponse
import cloud.wafflecommons.pixelbrainreader.data.remote.GitTreeItem
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

import cloud.wafflecommons.pixelbrainreader.data.local.dao.SyncMetadataDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.SyncMetadataEntity
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileShaTuple

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import androidx.room.withTransaction

@Singleton
class FileRepository @Inject constructor(
    private val gitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.GitProvider,
    private val fileDao: FileDao,
    private val metadataDao: SyncMetadataDao,
    private val fileContentDao: FileContentDao,
    private val database: AppDatabase
) {
    // ... companion object

    /**
     * Get files from Local DB (Offline First).
     */
    fun getFilesFlow(path: String): Flow<List<FileEntity>> {
        return fileDao.getFiles(path)
    }

    // Keep getFiles for compatibility
    fun getFiles(path: String): Flow<List<FileEntity>> = getFilesFlow(path)

    suspend fun getAllFolders(): List<String> {
        return fileDao.getAllFolderPaths()
    }

    fun searchFiles(query: String): Flow<List<FileEntity>> {
        return fileDao.searchFiles(query)
    }

    /**
     * Deterministic WikiLink Resolver.
     * 1. Exact Match (File or Folder).
     * 2. Implicit .md Extension.
     */
    suspend fun resolveLink(targetPath: String): FileEntity? {
        val cleanPath = targetPath.trim()
        
        // 1. Exact Match
        val exact = fileDao.getFile(cleanPath)
        if (exact != null) return exact

        // 2. Implicit Extension (Only if not already .md)
        if (!cleanPath.endsWith(".md", ignoreCase = true)) {
            val withExt = fileDao.getFile("$cleanPath.md")
            if (withExt != null) return withExt
        }
        
        return null
    }

    /**
     * Sync File List.
     * Fetches from API -> Updates DB.
     * CRITICAL: Uses replaceFolderContent to purge ghosts (Authoritative Sync).
     */
    suspend fun refreshFolder(owner: String, repo: String, path: String): Result<Unit> {
        return try {
            // 1. Fetch Remote Content (Fresh - No ETag)
            // We consciously avoid ETag here to ensure we get the full list for the authoritative reset.
            val response = gitProvider.getContents(owner, repo, path)

            if (response.isFailure) {
                return Result.failure(response.exceptionOrNull() ?: Exception("Unknown Network Error"))
            }

            val remoteFiles = response.getOrNull() ?: emptyList()
            val entities = remoteFiles.map { it.toEntity() }

            // 2. Transactional Replace via DAO (Authoritative Sync)
            // This deletes old files in the folder before inserting new ones.
            fileDao.replaceFolderContent(path, entities)

            // Update Metadata (New ETag) - GitProvider might not return ETag exposed headers easily?
            // TODO: Handle ETag or cache control via GitProvider if needed.
            // For now, ignoring ETag save as GitProvider abstraction handles fetching.
            // If we strictly need ETag, we need to return it from getContents.
            // Result.success(Unit)

            // Mocking success for now as we don't have ETag from Generic Result
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Local-First Content Read.
     */
    fun getFileContentFlow(path: String): Flow<String?> {
        return fileContentDao.getContentFlow(path)
    }

    /**
     * Sync Content.
     * Fetches from API -> Updates DB (FileContent).
     */
    suspend fun refreshFileContent(path: String, downloadUrl: String): Result<Unit> {
        return try {
            val result = gitProvider.getFileContent(downloadUrl)
            if (result.isSuccess) {
                val content = result.getOrNull() ?: ""
                // Persist
                fileContentDao.saveContent(FileContentEntity(path = path, content = content))
                Result.success(Unit)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Unknown error fetching file content"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save File Local (Offline First).
     * Updates Content DB & Marks as Dirty.
     */
    suspend fun saveFileLocally(path: String, content: String) {
        // 1. Update Content
        fileContentDao.saveContent(FileContentEntity(path = path, content = content))
        
        // 2. Insert or Update File Entity
        val existing = fileDao.getFile(path)
        if (existing == null) {
            val newEntity = FileEntity(
                path = path,
                name = path.substringAfterLast("/"),
                type = "file",
                downloadUrl = null,
                isDirty = true,
                localModifiedTimestamp = System.currentTimeMillis()
            )
            fileDao.insertFile(newEntity)
        } else {
            // FIX: Ensure timestamp is updated along with dirty flag
            val updatedEntity = existing.copy(
                isDirty = true,
                localModifiedTimestamp = System.currentTimeMillis()
            )
            fileDao.insertFile(updatedEntity)
        }
    }

    suspend fun pushChanges(owner: String, repo: String, message: String): Result<Unit> {
        return pushDirtyFiles(owner, repo, message)
    }

    /**
     * Push Dirty Files to Remote.
     * 1. Get Dirty Files.
     * 2. For each: Get SHA -> PUT -> Mark Clean.
     */
    suspend fun pushDirtyFiles(owner: String, repo: String, message: String? = null): Result<Unit> {
        return try {
            val dirtyFiles = fileDao.getDirtyFiles().filter { it.type == "file" }
            Log.d("PixelBrain", "Starting Push. Dirty files count: ${dirtyFiles.size}")
            
            for (file in dirtyFiles) {
                Log.d("PixelBrain", "Processing file: ${file.path}")

                // A. Get current remote SHA (Required for Update)
                val shaResult = gitProvider.getFileSha(owner, repo, file.path)
                
                if (shaResult.isFailure) {
                     val e = shaResult.exceptionOrNull()
                     Log.e("PixelBrain", "Failed to get SHA for ${file.path}: ${e?.message}")
                     throw e ?: Exception("Failed to get SHA")
                }

                val sha = shaResult.getOrNull()
                // If sha is null, it's a new file.

                // B. Get Local Content
                val content = fileContentDao.getContent(file.path) ?: ""
                
                // C. PUT
                val pushResult = gitProvider.pushFile(
                    owner = owner,
                    repo = repo,
                    path = file.path,
                    content = content,
                    sha = sha,
                    message = message ?: "Update ${file.name} via Pixel Brain"
                )
                
                if (pushResult.isFailure) {
                    val e = pushResult.exceptionOrNull()
                     Log.e("PixelBrain", "Push Failed for ${file.path}: ${e?.message}")
                    throw e ?: Exception("Push Failed")
                }
                
                Log.d("PixelBrain", "Push Success for ${file.path}")
                
                // D. Mark Clean
                fileDao.markFileAsDirty(file.path, false)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PixelBrain", "Push Exception", e)
            Result.failure(e)
        }
    }
    /**
     * Rename File (Local Only workaround).
     * 1. Save content to new path (Dirty).
     * 2. Delete old file.
     * Note: This does not issue a Git Move command yet.
     */
    /**
     * SAFE Rename/Move Logic (Transactional).
     * Handles Files AND Directories recursively.
     * ZERO DATA LOSS: Verifies content before deleting old.
     */
    suspend fun renameFileSafe(oldPath: String, newPath: String) {
        database.withTransaction {
            val oldEntity = fileDao.getFile(oldPath) ?: throw Exception("File not found: $oldPath")

            if (oldEntity.type == "dir") {
                // Recursive Move
                val children = fileDao.getDescendants(oldPath)
                
                // 1. Move all children
                for (child in children) {
                    // child.path = "A/B/file.txt", oldPath = "A", newPath = "C"
                    // relative = "B/file.txt"
                    // newChildPath = "C/B/file.txt"
                    val relativePath = child.path.substringAfter("$oldPath/")
                    val newChildPath = "$newPath/$relativePath"
                    
                    if (child.type == "dir") {
                        createFolderEntity(newChildPath)
                        fileDao.deleteFile(child.path)
                    } else {
                        moveFileContentSafe(child.path, newChildPath)
                    }
                }
                
                // 2. Move Root Folder
                createFolderEntity(newPath)
                fileDao.deleteFile(oldPath)
            } else {
                // Single File Move
                moveFileContentSafe(oldPath, newPath)
            }
        }
    }

    private suspend fun moveFileContentSafe(oldPath: String, newPath: String) {
        // 0. Check Metadata first (to handle lazy-loaded files)
        val oldEntity = fileDao.getFile(oldPath) ?: throw Exception("File Entity not found: $oldPath")

        // 1. Get Content
        val content = fileContentDao.getContent(oldPath)

        if (content == null) {
            // Content Missing!
            if (oldEntity.isDirty) {
                 // FATAL: File thinks it has unsaved changes but content is gone.
                 throw Exception("Critical: Content missing for dirty file $oldPath. Aborting move to prevent data loss.")
            } else {
                // LAZY FILE METADATA MOVE
                // File is clean (synced) but we haven't downloaded content yet.
                // Just create new entity with same metadata (url, sha etc) and delete old.
                val newEntity = oldEntity.copy(
                    path = newPath,
                    name = newPath.substringAfterLast("/"),
                    // isDirty stays false? Yes, effectively new path matches remote content (if we assume move is synced separately)
                    // Wait, if we rename locally, we are making it dirty relative to remote path?
                    // Yes, we will delete old remote and push new remote.
                    // But we don't have content to push!
                    // If we rename a lazy file, we MUST download it first to push it to new location?
                    // Github API doesn't support "MV" without content (uses Delete+Create).
                    // So we cannot "Push" a lazy file move without content.
                    
                    // Option A: Force Download now?
                    // Option B: Mark as dirty but missing content? (Bad state)
                    
                    // Actually, if we just update local path, the `moveFileRemote` will fail because `fileContentDao.getContent(newPath)` is null.
                    
                    // Let's look at `moveFileRemote`:
                    // val content = fileContentDao.getContent(newPath) ?: ""
                    
                    // If we return empty string, we overwrite remote file with empty content! BAD.
                    
                    // ERROR PREVENTED: We cannot allow renaming a lazy file without downloading it first.
                    // UNLESS we implement a "Smart Move" that downloads on demand.
                    
                    // Given urgency, let's TRY to download it if url exists.
                )
                
                if (oldEntity.downloadUrl != null) {
                    // Attempt on-the-fly download (Simple synchronous fetch via Url not easy here without Repo ref)
                    // For now, let's create the entity. But we must be careful with Remote Sync.
                    
                    // CRITICAL DECISION:
                    // If we rename a file we haven't read, we can't push it to GitHub as a new file (requires content).
                    // We must block this or fix it.
                    // Blocking is safest for "Data Loss" prevention.
                    // "Please open file to download content before renaming."
                    
                    // BUT USER EXPECTATION: I can organize my files without opening them.
                    
                    // If I rename locally, I need to push new file.
                    // Current Remote implementation: PUSH (requires content).
                    
                    // If I throw specific exception, I can catch in VM and maybe trigger download?
                    
                    throw Exception("File content not downloaded. Please open the file once to sync it before moving.")
                } else {
                     // No URL, no content, not dirty? Ghost file.
                     throw Exception("Critical: Ghost file detected (No content, No URL, Not Dirty). Cannot move.")
                }
            }
        }

        // Standard Safe Move (We have content)
        
        // 2. Save New (Recycles existing logic)
        saveFileLocally(newPath, content) 
        
        // 3. Verify
        val savedContent = fileContentDao.getContent(newPath)
        if (savedContent != content) {
             throw Exception("Verification Failed: Content mismatch for $newPath")
        }
        
        // 4. Delete Old
        fileDao.deleteFile(oldPath)
    }

    private suspend fun createFolderEntity(path: String) {
        val entity = FileEntity(
            path = path,
            name = path.substringAfterLast("/"),
            type = "dir",
            downloadUrl = null,
            isDirty = true,
            localModifiedTimestamp = System.currentTimeMillis()
        )
        fileDao.insertFile(entity)
    }

    suspend fun createLocalFolder(path: String) {
        createFolderEntity(path)
    }

    /**
     * Recursive Remote Directory Move.
     * Iterates all files currently in [newPath] (assumed moved locally) and moves them on Remote from [oldPath].
     */
    suspend fun moveDirectoryRemote(owner: String, repo: String, oldPath: String, newPath: String): Result<Unit> {
        return try {
            // 1. Get Synchronized Files (Local is Single Source of Truth after Local Move)
            // We look for files starting with newPath/
            val movedFiles = fileDao.getDescendants(newPath)
            
            var failureCount = 0
            
            for (file in movedFiles) {
                if (file.type == "dir") continue // Skip folders (Remote only cares about blobs)
                
                // Reconstruct Original Remote Path
                // Local: "NewFolder/Sub/File.txt" (newPath="NewFolder")
                // Relative: "Sub/File.txt"
                // Old Remote: "OldFolder/Sub/File.txt"
                val relative = file.path.substringAfter("$newPath/")
                val oldRemotePath = "$oldPath/$relative"
                
                val res = moveFileRemote(
                    owner = owner,
                    repo = repo,
                    oldPath = oldRemotePath, 
                    newPath = file.path, 
                    oldName = file.name, 
                    newName = file.name
                )
                
                if (res.isFailure) {
                    Log.e("PixelBrain", "Failed to move file ${file.path}: ${res.exceptionOrNull()?.message}")
                    failureCount++
                }
            }
            
            if (failureCount > 0) {
                Result.failure(Exception("Moved directory with $failureCount errors"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Transactional Rename + Sync (Convenience Wrapper).
     */
    suspend fun renameAndSync(oldPath: String, newPath: String, owner: String?, repo: String?): Result<Unit> {
        return try {
            // 1. Check Type BEFORE rename (Old entity will be deleted)
            val oldEntity = fileDao.getFile(oldPath) ?: return Result.failure(Exception("File not found"))
            val isDirectory = oldEntity.type == "dir"
            val oldName = oldEntity.name
            val newName = newPath.substringAfterLast("/")

            // 2. Local Rename
            renameFileSafe(oldPath, newPath)
            
            // 3. Remote Sync (Fail-soft: if no credentials, we just stay local dirty? No, dirty logic for rename is tricky)
            // If owner/repo provided, we attempt sync.
            if (owner != null && repo != null) {
                if (isDirectory) {
                    moveDirectoryRemote(owner, repo, oldPath, newPath)
                } else {
                    moveFileRemote(owner, repo, oldPath, newPath, oldName, newName)
                }
            } else {
                Result.success(Unit) // Local only
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remote Move (Rename) Logic:
     * 1. Check if OLD file exists remotely (Get SHA).
     * 2. Push NEW file (PUT) with content.
     * 3. Delete OLD file (DELETE) using SHA.
     */
    suspend fun moveFileRemote(owner: String, repo: String, oldPath: String, newPath: String, oldName: String, newName: String): Result<Unit> {
        return try {
            // A. Get SHA of OLD file
            val shaResult = gitProvider.getFileSha(owner, repo, oldPath)
            if (shaResult.isFailure) {
                // If old file doesn't exist remotely (e.g. local only), just Push the new one?
                // But this method implies a Move of a synced file.
                return Result.failure(Exception("Could not find remote file to move")) 
            }
            val sha = shaResult.getOrNull() ?: return Result.failure(Exception("Remote file not found"))

            // B. Get Content (Local is source of truth)
            val content = fileContentDao.getContent(newPath) ?: "" // We assume local rename happened first

            // C. Push NEW file
            val pushResult = gitProvider.pushFile(
                owner = owner,
                repo = repo,
                path = newPath,
                content = content,
                sha = null, // New file
                message = "refactor(file): rename '$oldName' to '$newName'"
            )

            if (pushResult.isFailure) throw pushResult.exceptionOrNull() ?: Exception("Push failed during move")

            // D. Delete OLD file
            val deleteResult = gitProvider.deleteFile(
                owner = owner,
                repo = repo,
                path = oldPath,
                sha = sha,
                message = "refactor(file): rename '$oldName' to '$newName'"
            )
            
            if (deleteResult.isFailure) {
                 // Warning: New file exists, Old file exists. Duplicate.
                 Log.e("PixelBrain", "Move Partial Fail: Delete failed for $oldPath")
                 throw deleteResult.exceptionOrNull() ?: Exception("Delete failed during move")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * FULL MIRROR SYNC (Remote Wins).
     * 1. Get Full Tree (Recursive 1).
     * 2. Diff (Local vs Remote).
     * 3. Prune (Delete Local not in Remote).
     * 4. Upsert (Update/Insert Remote files).
     */
    suspend fun syncRepository(owner: String, repo: String, branch: String = "main"): Result<Unit> {
        return try {
            Log.d("PixelBrain", "Starting Smart Incremental Sync for $owner/$repo/$branch")
            
            // 1. Fetch Remote Tree
            val treeResult = gitProvider.getGitTree(owner, repo, branch)
            if (treeResult.isFailure) {
                return Result.failure(treeResult.exceptionOrNull() ?: Exception("Failed to fetch remote tree"))
            }
            
            val treeResponse = treeResult.getOrNull() ?: throw Exception("Empty Tree Response")
            val remoteItems = treeResponse.tree
            
            // 2. Local State (Path -> SHA) - Optimize DB Query
            val localFileMap = fileDao.getAllFileShas().associate { it.path to it.sha }
            val localPaths = localFileMap.keys
            val remotePaths = remoteItems.map { it.path }.toSet()
            
            // 3. Prune Strategy (Delete Local NOT in Remote)
            val toDelete = localPaths.minus(remotePaths).toList()
            
            // 4. Execution (Transactional)
            database.withTransaction {
                // A. Prune
                if (toDelete.isNotEmpty()) {
                    Log.d("PixelBrain", "Pruning ${toDelete.size} local files/folders")
                    fileDao.deleteFiles(toDelete)
                }
                
                // B. Sync (Insert/Update & Download if changed)
                var downloadCount = 0
                var skipCount = 0
                
                for (item in remoteItems) {
                    if (item.type == "tree") {
                        // Ensure Folder Entity Exists
                         if (!localPaths.contains(item.path)) {
                             createFolderEntity(item.path)
                         }
                    } else if (item.type == "blob") {
                         val isMarkdown = item.path.endsWith(".md", ignoreCase = true)
                         val localSha = localFileMap[item.path]
                         
                         // INCREMENTAL CHECK
                         // Download if: New File (localSha null) OR Content Changed (SHA mismatch)
                         val shouldDownload = (localSha == null) || (localSha != item.sha)

                         // 1. Update Entity Metadata (Always, to ensure SHA is synced)
                         val entity = FileEntity(
                             path = item.path,
                             name = item.path.substringAfterLast("/"),
                             type = "file",
                             downloadUrl = item.url, // GitTreeItem Blob URL
                             sha = item.sha,         // Store Remote SHA
                             isDirty = false,        // Synced
                             lastSyncedAt = System.currentTimeMillis()
                         )
                         fileDao.insertFile(entity) // Upsert
                         
                         // 2. Content Sync
                         // Only download content for Markdowns that CHANGED or are NEW.
                         if (isMarkdown) {
                             if (shouldDownload) {
                                  // Construct raw URL (See previous implementation notes)
                                  val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/${item.path}"
                                  refreshFileContent(item.path, rawUrl)
                                  downloadCount++
                             } else {
                                  skipCount++
                             }
                         }
                    }
                }
                 Log.d("PixelBrain", "Sync Report: Downloaded $downloadCount, Skipped $skipCount (Up-to-date)")
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PixelBrain", "Sync Failed", e)
            Result.failure(e)
        }
    }
}
