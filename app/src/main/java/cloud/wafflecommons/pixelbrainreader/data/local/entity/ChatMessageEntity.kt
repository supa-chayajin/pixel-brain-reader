package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One turn in a persisted Gemini Nano chat session.
 *
 * `mode` discriminates between the two chat surfaces ("RAG" vs "CREATIVE") so a
 * single table serves both with cheap per-mode reads via the composite index.
 * Storage strings are intentionally decoupled from the UI enum names — the
 * ViewModel maps `ChatMode` -> storage on write.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["mode", "timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val mode: String,        // "RAG" or "CREATIVE"
    val role: String,        // "USER" or "MODEL"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // RAG citations attached to MODEL turns; empty for USER turns and CREATIVE responses.
    // Serialized via RoomTypeConverters.fromStringList.
    val sources: List<String> = emptyList()
)
