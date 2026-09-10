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
}
