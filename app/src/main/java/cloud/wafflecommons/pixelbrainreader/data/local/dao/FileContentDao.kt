package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileContentDao {
    @Query("SELECT content FROM file_contents WHERE path = :path")
    fun getContentFlow(path: String): Flow<String?>

    @Query("SELECT content FROM file_contents WHERE path = :path")
    suspend fun getContent(path: String): String?

    // Upsert, never REPLACE: REPLACE deletes the old row without firing delete triggers (SQLite
    // runs them only with recursive_triggers on), which would leave stale terms in the full-text
    // index that Room keeps in sync with triggers on this table.
    @Upsert
    suspend fun saveContent(entity: FileContentEntity)

    @Upsert
    suspend fun saveContents(entities: List<FileContentEntity>)

    @Query("DELETE FROM file_contents WHERE path IN (:paths)")
    suspend fun deleteContents(paths: List<String>)
}
