package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import kotlinx.coroutines.flow.Flow

/** A note's text, as `file_contents` mirrors it. */
data class PathContent(val path: String, val content: String)

@Dao
interface FileContentDao {
    /** Every mirrored note under [prefix] (a folder path ending with `/`; empty = the whole vault). */
    @Query("SELECT path, content FROM file_contents WHERE substr(path, 1, length(:prefix)) = :prefix")
    suspend fun contentsUnder(prefix: String): List<PathContent>

    /** The paths only, for a count that needn't read every note (the shutdown's inbox count). */
    @Query("SELECT path FROM file_contents WHERE substr(path, 1, length(:prefix)) = :prefix")
    suspend fun pathsUnder(prefix: String): List<String>

    /** Changes whenever a row under [prefix] is added, removed or rewritten: a cheap cache key. */
    @Query(
        "SELECT COUNT(*) || ':' || COALESCE(MAX(lastSyncedAt), 0) || ':' || COALESCE(SUM(LENGTH(content)), 0) " +
            "FROM file_contents WHERE substr(path, 1, length(:prefix)) = :prefix"
    )
    suspend fun signatureUnder(prefix: String): String

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
