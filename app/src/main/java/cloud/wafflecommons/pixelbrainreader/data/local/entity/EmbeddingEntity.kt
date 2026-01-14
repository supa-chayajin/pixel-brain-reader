package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["path"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fileId"])]
)
data class EmbeddingEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(), // UUID
    val fileId: String,   // Filename/Path
    val content: String,  // The Chunk
    val vector: List<Float>, // The Embedding
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingEntity

        if (id != other.id) return false
        if (fileId != other.fileId) return false
        if (content != other.content) return false
        // List equality
        if (vector != other.vector) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + vector.hashCode()
        return result
    }
}
