package cloud.wafflecommons.pixelbrainreader.ui.homeconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeConfigViewModel @Inject constructor(
    private val repository: ChoreRepository
) : ViewModel() {

    val allRooms: StateFlow<List<HomeRoomEntity>> = repository.getAllRoomsStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val choresByRoom: StateFlow<Map<String, List<ChoreEntity>>> = repository.getRoomsWithChoresStream()
        .map { list ->
            // Map the Room UUID -> List of Chores
            list.associate { it.room.id to it.chores }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun createEmptyRoom(name: String, icon: String = "home", color: String = "#808080") {
        viewModelScope.launch(Dispatchers.IO) {
            val roomEntity = HomeRoomEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim().takeIf { it.isNotEmpty() } ?: "New Room",
                icon = icon,
                color = color
            )
            repository.upsertRoom(roomEntity)
        }
    }

    fun upsertRoom(id: String?, name: String, icon: String, color: String, sortOrder: Int = 0) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val validName = name.trim().takeIf { it.isNotEmpty() } ?: "Unnamed room"
            val roomEntity = HomeRoomEntity(
                id = id ?: UUID.randomUUID().toString(),
                name = validName,
                icon = icon,
                color = color,
                sortOrder = sortOrder
            )
            repository.upsertRoom(roomEntity)
        }
    }

    fun deleteRoom(room: HomeRoomEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.deleteRoom(room)
        }
    }

    fun upsertChore(chore: ChoreEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.insertChore(chore) // Repository insert handles conflict REPLACE
        }
    }

    fun deleteChore(chore: ChoreEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.deleteChore(chore)
        }
    }
}
