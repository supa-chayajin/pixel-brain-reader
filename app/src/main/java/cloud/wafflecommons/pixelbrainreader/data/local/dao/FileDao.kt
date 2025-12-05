package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    // FIX applied for Root Path:
    // If parentPath is empty, we check for paths with NO slashes (top level).
    // If parentPath is NOT empty, we use the LIKE clause for children.
    // NOTE: SQLite 'GLOB' could be used for case-sensitive matching, but here we use conditional logic.
    @Query("SELECT * FROM files WHERE (:parentPath = '' AND path NOT LIKE '%/%') OR (:parentPath != '' AND (path = :parentPath OR path LIKE :parentPath || '/%'))")
    fun getFiles(parentPath: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files")
    fun getAllFiles(): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<FileEntity>)

    @Query("DELETE FROM files")
    suspend fun clearAll()
}
