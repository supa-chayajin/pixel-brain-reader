package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.ScratchDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScratchRepository @Inject constructor(
    private val scratchDao: ScratchDao
) {
    fun getActiveScraps(): Flow<List<ScratchNoteEntity>> = scratchDao.getActiveScraps()

    suspend fun saveScrap(content: String, color: Int = 0xFF000000.toInt()) = withContext(Dispatchers.IO) {
        val scrap = ScratchNoteEntity(content = content, color = color)
        scratchDao.insertScrap(scrap)
    }

    suspend fun updateScrap(scrap: ScratchNoteEntity) = withContext(Dispatchers.IO) {
        scratchDao.updateScrap(scrap)
    }

    suspend fun deleteScrap(scrap: ScratchNoteEntity) = withContext(Dispatchers.IO) {
        scratchDao.deleteScrap(scrap)
    }

    suspend fun getScrapById(id: String): ScratchNoteEntity? = withContext(Dispatchers.IO) {
        scratchDao.getScrapById(id)
    }
}
