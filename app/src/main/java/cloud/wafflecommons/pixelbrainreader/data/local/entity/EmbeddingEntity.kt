package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed chunk for the local RAG pipeline.
 *
 * Security note: when [isPrivate] is true, [content] holds a Base64-encoded
 * AES-256-GCM ciphertext produced by CryptoManager (with the user's vault
 * password as the PBKDF2 input). VectorSearchEngine decrypts just-in-time
 * for the top-K hits at query time.
 *
 * Residual risk (knowingly accepted): the [vector] itself is computed from
 * plaintext and stored unencrypted — cosine similarity can't be performed on
 * ciphertext. Embedding-inversion attacks are an active research area; an
 * attacker with raw DB access AND a large enough sample of embeddings could
 * partially reconstruct semantic content. This is the same posture as any
 * local-vector RAG store; if it ever becomes unacceptable, the alternative
 * is to exclude private notes from RAG entirely rather than partial protection.
 */
@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["path"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fileId"])]
)
data class EmbeddingEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val fileId: String,
    val content: String,
    val vector: List<Float>,
    val isPrivate: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingEntity

        if (id != other.id) return false
        if (fileId != other.fileId) return false
        if (content != other.content) return false
        if (isPrivate != other.isPrivate) return false
        if (vector != other.vector) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + vector.hashCode()
        return result
    }
}
