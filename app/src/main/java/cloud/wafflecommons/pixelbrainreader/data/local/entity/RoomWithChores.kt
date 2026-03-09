package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class RoomWithChores(
    @Embedded val room: HomeRoomEntity,
    @Relation(
         parentColumn = "id",
         entityColumn = "roomId"
    )
    val chores: List<ChoreEntity>
)
