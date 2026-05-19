package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChatDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over [ChatDao] for Gemini Nano chat history.
 *
 * Storage strings ("RAG" / "CREATIVE") are intentionally a contract at this boundary
 * so the data layer never imports the UI's ChatMode enum.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao
) {

    fun streamMessages(mode: String): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesByMode(mode)

    suspend fun addMessage(message: ChatMessageEntity) =
        chatDao.insertMessage(message)

    /**
     * Last [limit] messages for [mode], chronological order.
     * DAO returns DESC for index efficiency; we reverse to give the prompt
     * formatter natural reading order.
     */
    suspend fun recentForPrompt(mode: String, limit: Int = 6): List<ChatMessageEntity> =
        chatDao.getLastNByMode(mode, limit).reversed()

    suspend fun clear(mode: String) =
        chatDao.clearHistoryByMode(mode)
}
