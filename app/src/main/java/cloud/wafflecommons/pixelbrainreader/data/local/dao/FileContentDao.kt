package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileContentDao {
    @Query("SELECT content FROM file_contents WHERE path = :path")
    fun getContentFlow(path: String): Flow<String?>

    @Query("SELECT content FROM file_contents WHERE path = :path")
    suspend fun getContent(path: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveContent(entity: FileContentEntity)
}
