package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scratch_notes")
data class ScratchNoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val color: Int = 0xFF000000.toInt(), // OLED Black default or pastel selection
    val isPromoted: Boolean = false
)
