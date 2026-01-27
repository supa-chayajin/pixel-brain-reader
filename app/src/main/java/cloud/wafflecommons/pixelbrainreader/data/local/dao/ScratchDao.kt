package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.*
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScratchDao {
    @Query("SELECT * FROM scratch_notes WHERE isPromoted = 0 ORDER BY createdAt DESC")
    fun getActiveScraps(): Flow<List<ScratchNoteEntity>>

    @Query("SELECT * FROM scratch_notes WHERE isPromoted = 0 ORDER BY createdAt DESC")
    suspend fun getActiveScrapsSync(): List<ScratchNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScrap(scrap: ScratchNoteEntity)

    @Update
    suspend fun updateScrap(scrap: ScratchNoteEntity)

    @Delete
    suspend fun deleteScrap(scrap: ScratchNoteEntity)

    @Query("SELECT * FROM scratch_notes WHERE id = :id")
    suspend fun getScrapById(id: String): ScratchNoteEntity?
}
