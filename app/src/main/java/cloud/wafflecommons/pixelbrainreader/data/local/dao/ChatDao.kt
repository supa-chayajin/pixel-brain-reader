package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /** Full history for a given mode, ASC by timestamp — what the UI renders. */
    @Query("SELECT * FROM chat_messages WHERE mode = :mode ORDER BY timestamp ASC")
    fun getMessagesByMode(mode: String): Flow<List<ChatMessageEntity>>

    /**
     * Last [limit] messages for the sliding window injected into the ML Kit prompt.
     * DESC at the SQL level (cheap with the composite index), reversed by the caller
     * so the prompt reads chronologically.
     */
    @Query("SELECT * FROM chat_messages WHERE mode = :mode ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastNByMode(mode: String, limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE mode = :mode")
    suspend fun clearHistoryByMode(mode: String)
}
