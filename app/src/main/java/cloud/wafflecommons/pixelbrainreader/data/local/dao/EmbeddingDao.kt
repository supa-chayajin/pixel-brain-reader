package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity

/** Cheap fingerprint of the embeddings table: it changes whenever rows are added, replaced or removed. */
data class EmbeddingTableSignature(val count: Int, val newest: Long?, val total: Long?)

/** A stored chunk without its text, for similarity scoring. */
data class EmbeddingVectorRow(val id: String, val fileId: String, val isPrivate: Boolean, val vector: List<Float>)

/** A stored chunk's text. */
data class EmbeddingContentRow(val id: String, val content: String)

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(embeddings: List<EmbeddingEntity>)

    /** A note's stored passages: the indexer reuses every one whose text didn't change. */
    @Query("SELECT * FROM embeddings WHERE fileId = :fileId")
    suspend fun rowsFor(fileId: String): List<EmbeddingEntity>

    /**
     * Notes whose content changed: their vectors stay, so the indexer reuses each passage that is
     * still there, but they are no longer current (-1 = EmbeddingEntity.PENDING), so the note is
     * re-embedded.
     */
    @Query("UPDATE embeddings SET lastUpdated = -1 WHERE fileId IN (:fileIds)")
    suspend fun markStale(fileIds: List<String>)

    /** The oldest stamp of a note's passages: EmbeddingEntity.PENDING while any of them is pending or stale. */
    @Query("SELECT MIN(lastUpdated) FROM embeddings WHERE fileId = :fileId")
    suspend fun oldestStamp(fileId: String): Long?

    /** Moves a note's stamps up to [stamp]: its vectors are still current for its new file time. */
    @Query("UPDATE embeddings SET lastUpdated = :stamp WHERE fileId = :fileId AND lastUpdated < :stamp")
    suspend fun restamp(fileId: String, stamp: Long)

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) AS count, MAX(lastUpdated) AS newest, SUM(lastUpdated) AS total FROM embeddings")
    suspend fun signature(): EmbeddingTableSignature

    @Query("SELECT id, fileId, isPrivate, vector FROM embeddings")
    suspend fun getAllVectors(): List<EmbeddingVectorRow>

    @Query("SELECT id, content FROM embeddings WHERE id IN (:ids)")
    suspend fun getContents(ids: List<String>): List<EmbeddingContentRow>

    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    suspend fun deleteByFileId(fileId: String)

    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    fun deleteByFileIdBlocking(fileId: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()

    @Query("DELETE FROM embeddings WHERE fileId NOT IN (SELECT path FROM files)")
    suspend fun deleteOrphans()

    /** Every embedded note's path, once. */
    @Query("SELECT DISTINCT fileId FROM embeddings")
    suspend fun fileIds(): List<String>
}

/**
 * A note's content didn't change but its file time moved on (a pull or a rebase rewrote it): if its
 * vectors were current for [oldMtime], they stay current for [newMtime] and the indexer never even
 * opens the note. Pending or stale vectors are left as they are.
 */
suspend fun EmbeddingDao.absorbMtimeDrift(fileId: String, oldMtime: Long, newMtime: Long) {
    val oldest = oldestStamp(fileId) ?: return
    if (oldest >= oldMtime) restamp(fileId, newMtime)
}
