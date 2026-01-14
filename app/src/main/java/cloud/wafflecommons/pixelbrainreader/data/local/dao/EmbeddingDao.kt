package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {
    @androidx.room.Upsert
    suspend fun upsert(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    suspend fun deleteEmbeddingsForFile(fileId: String)

    @Query("SELECT * FROM embeddings WHERE fileId = :fileId")
    suspend fun getEmbeddingsForFile(fileId: String): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>
}
