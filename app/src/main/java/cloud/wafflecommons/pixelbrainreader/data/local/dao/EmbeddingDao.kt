package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE filePath = :filePath")
    suspend fun deleteEmbeddingsForFile(filePath: String)

    @Query("SELECT * FROM embeddings WHERE filePath = :filePath ORDER BY chunkIndex ASC")
    suspend fun getEmbeddingsForFile(filePath: String): List<EmbeddingEntity>

    // Placeholder for Vector Search (will be replaced by SQLite specific math or custom function)
    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>
}
