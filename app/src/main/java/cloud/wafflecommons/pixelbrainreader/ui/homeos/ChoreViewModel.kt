package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ChoreViewModel @Inject constructor(
    private val choreRepository: ChoreRepository,
    private val calculateChoreEntropyUseCase: CalculateChoreEntropyUseCase,
    private val grantXpUseCase: GrantXpUseCase,
    private val syncOrchestrator: cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
) : ViewModel() {

    // Sorted and grouped by Room natively for the UI
    val groupedChores: StateFlow<Map<String, List<ChoreUiModel>>> = choreRepository.getRoomsWithChoresStream()
        .map { roomsWithChores ->
            val result = mutableMapOf<String, List<ChoreUiModel>>()
            for (roomData in roomsWithChores) {
                // Archived chores are hidden from the dashboard (still preserved on export).
                val visibleChores = roomData.chores.filter { !it.archived }
                val uiModels = calculateChoreEntropyUseCase(visibleChores)
                // We show empty rooms if we want, or filter them. Here we show all known rooms.
                result[roomData.room.name] = uiModels.sortedByDescending { it.dirtinessPercentage }
            }
            result
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val allRooms: StateFlow<List<HomeRoomEntity>> = choreRepository.getAllRoomsStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    val isSyncing: StateFlow<cloud.wafflecommons.pixelbrainreader.data.sync.SyncState> = syncOrchestrator.syncState

    fun triggerSync() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            syncOrchestrator.executeFullSyncCycle()
        }
    }

    fun doChore(choreId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Find the chore in the current state to check for anti-cheat and calculate dynamic XP
            val choreMap = groupedChores.value
            val targetUiModel = choreMap.values.flatten().find { it.entity.id == choreId } ?: return@launch
            
            // ANTI-CHEAT: If it was cleaned today or yesterday (dirtiness very low), grant no XP
            if (targetUiModel.dirtinessPercentage < 5f) {
                // We still update the date just for accuracy, but we abort the XP sequence
                choreRepository.updateLastDoneDate(choreId, LocalDate.now().toString())
                return@launch
            }

            // Normal gamification path
            var finalXp = targetUiModel.entity.baseEffort.toDouble()

            // Restoring Order Bonus: Give extra +15 XP if it was deeply dirty (> 100%)
            if (targetUiModel.dirtinessPercentage >= 100f) {
                finalXp += 15.0
            }

            // Persistence
            choreRepository.updateLastDoneDate(choreId, LocalDate.now().toString())

            // Gamification integration
            grantXpUseCase.executeCustom(
                attribute = Attribute.END, // Physical cleaning maps to Endurance
                xpBase = finalXp,
                sourceId = "Cleaned: ${targetUiModel.entity.name}"
            )
        }
    }

    fun addChoreWithRoomId(name: String, roomId: String, frequencyDays: Int, baseEffort: Int, pastDays: Int = 0) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val randomId = java.util.UUID.randomUUID().toString()
            val safeFreq = frequencyDays.takeIf { it > 0 } ?: 1
            
            choreRepository.insertChore(
                cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity(
                    id = randomId,
                    name = name.trim(),
                    roomId = roomId,
                    baseEffort = baseEffort,
                    frequencyDays = safeFreq,
                    lastDoneDate = LocalDate.now().minusDays(pastDays.toLong()).toString()
                )
            )
        }
    }

    fun addChore(name: String, room: String, frequencyDays: Int, baseEffort: Int, pastDays: Int = 0) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val randomId = java.util.UUID.randomUUID().toString()
            val safeRoomName = room.trim().takeIf { it.isNotEmpty() } ?: "Uncategorized"
            val safeFreq = frequencyDays.takeIf { it > 0 } ?: 1
            
            // Bridge: Find existing Room UUID by name, or create it dynamically
            val allRoomsList = choreRepository.getAllRoomsStream().first()
            var matchingRoom = allRoomsList.find { it.name.equals(safeRoomName, ignoreCase = true) }
            
            if (matchingRoom == null) {
                matchingRoom = cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = safeRoomName,
                )
                choreRepository.upsertRoom(matchingRoom)
            }
            
            addChoreWithRoomId(name, matchingRoom.id, safeFreq, baseEffort, pastDays)
        }
    }

    fun addDebugChore() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            addChore(
                name = "Chore ${java.util.UUID.randomUUID().toString().take(4)}",
                room = listOf("Kitchen", "Bathroom", "Living Room").random(),
                baseEffort = listOf(10, 25, 50).random(),
                frequencyDays = listOf(3, 7, 14, 30).random(),
                pastDays = (0..20).random()
            )
        }
    }
}
