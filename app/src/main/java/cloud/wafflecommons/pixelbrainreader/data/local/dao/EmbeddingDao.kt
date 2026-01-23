package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: EmbeddingEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(embedding: EmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    suspend fun deleteByFileId(fileId: String)
    
    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    fun deleteByFileIdBlocking(fileId: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()

    @Query("DELETE FROM embeddings WHERE fileId NOT IN (SELECT path FROM files)")
    suspend fun deleteOrphans()
}
