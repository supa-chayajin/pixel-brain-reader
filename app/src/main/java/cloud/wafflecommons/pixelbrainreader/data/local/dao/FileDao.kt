package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FileDao {
    // FIX applied for Root Path:
    // If parentPath is empty, we check for paths with NO slashes (top level).
    // If parentPath is NOT empty, we use the LIKE clause for children.
    // NOTE: SQLite 'GLOB' could be used for case-sensitive matching, but here we use conditional logic.
    @Query("SELECT * FROM files WHERE (:parentPath = '' AND path NOT LIKE '%/%') OR (:parentPath != '' AND path LIKE :parentPath || '/%' AND path NOT LIKE :parentPath || '/%/%')")
    abstract fun getFiles(parentPath: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files")
    abstract fun getAllFiles(): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(files: List<FileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFile(file: FileEntity)

    @Query("SELECT * FROM files WHERE path = :path")
    abstract suspend fun getFile(path: String): FileEntity?

    @Query("UPDATE files SET isDirty = :isDirty WHERE path = :path")
    abstract suspend fun markFileAsDirty(path: String, isDirty: Boolean)

    @Query("SELECT * FROM files WHERE isDirty = 1")
    abstract suspend fun getDirtyFiles(): List<FileEntity>

    @Query("DELETE FROM files WHERE (:parentPath = '' AND path NOT LIKE '%/%') OR (:parentPath != '' AND path LIKE :parentPath || '/%' AND path NOT LIKE :parentPath || '/%/%')")
    abstract suspend fun deleteFilesByParent(parentPath: String)

    @Query("DELETE FROM files")
    abstract suspend fun clearAll()

    /**
     * Authoritative Sync (Purge & Replace)
     * Executed as a single transaction.
     */
    @androidx.room.Transaction
    open suspend fun replaceFolderContent(path: String, newFiles: List<FileEntity>) {
        deleteFilesByParent(path)
        insertAll(newFiles)
    }
}
