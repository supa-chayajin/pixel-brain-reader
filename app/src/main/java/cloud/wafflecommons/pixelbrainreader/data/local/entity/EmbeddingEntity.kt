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
            childColumns = ["filePath"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["filePath"])]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val chunkIndex: Int,
    val textChunk: String,
    
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val vector: ByteArray // Storing embedding vector as raw bytes (float array serialization)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingEntity

        if (id != other.id) return false
        if (filePath != other.filePath) return false
        if (chunkIndex != other.chunkIndex) return false
        if (textChunk != other.textChunk) return false
        if (!vector.contentEquals(other.vector)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + textChunk.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
